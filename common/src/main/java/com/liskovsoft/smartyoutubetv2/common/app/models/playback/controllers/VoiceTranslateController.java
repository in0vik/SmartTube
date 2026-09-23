package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.os.SystemClock;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerUI;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.common.vot.TranslationAudioPlayer;
import com.liskovsoft.smartyoutubetv2.common.vot.VotAudioSource;
import com.liskovsoft.smartyoutubetv2.common.vot.VotClient;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/** Manual English-to-Russian voice-over; keeps the main player as the playback clock. */
public final class VoiceTranslateController extends BasePlayerController {
    private static final String TAG = VoiceTranslateController.class.getSimpleName();
    private static final float ORIGINAL_VOLUME = 0.15f;
    private TranslationAudioPlayer mAudio;
    private Disposable mRequest;
    private boolean mEnabled;
    private boolean mDucked;
    private float mPreviousVolume;
    private float mPreferenceAtDuck;
    private float mCurrentVolume;
    private long mSpeechUntil;
    private int mGeneration;
    private VotAudioSource mSource;

    public void onFormatInfo(MediaItemFormatInfo info) {
        mSource = VotAudioSource.best(info);
    }

    private final Runnable mSync = new Runnable() {
        @Override
        public void run() {
            if (mEnabled && mAudio != null) {
                sync();
                Utils.postDelayed(this, 1000);
            }
        }
    };

    private final Runnable mVolumeTick = new Runnable() {
        @Override public void run() {
            if (!mDucked || mAudio == null || getPlayer() == null) return;
            updateVolume();
            Utils.postDelayed(this, 50);
        }
    };

    @Override
    public void onButtonClicked(int buttonId, int buttonState) {
        if (buttonId != R.id.action_voice_translate) return;
        if (mEnabled) stop(); else start();
    }

    private void start() {
        PlaybackView player = getPlayer();
        Video video = getVideo();
        if (player == null || video == null || video.videoId == null || video.isLive
                || player.getDurationMs() <= 0) {
            MessageHelpers.showMessage(getContext(), R.string.vot_unavailable);
            return;
        }
        mEnabled = true;
        setButton(PlayerUI.BUTTON_ON);
        MessageHelpers.showMessage(getContext(), R.string.vot_loading);
        int generation = ++mGeneration;
        String videoId = video.videoId;
        long durationMs = player.getDurationMs();
        VotAudioSource source = mSource;
        mRequest = Single.fromCallable(() -> new VotClient().translate(videoId, durationMs, source))
                .subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(url -> {
                    if (mEnabled && generation == mGeneration && getPlayer() != null) play(url);
                }, error -> {
                    if (mEnabled && generation == mGeneration) {
                        Log.e(TAG, "Translation request failed: %s", error.getMessage());
                        stop();
                        MessageHelpers.showMessage(getContext(), error.getMessage());
                    }
                });
    }

    private void play(String url) {
        PlaybackView player = getPlayer();
        if (player == null) return;
        mAudio = new TranslationAudioPlayer(getContext());
        mAudio.setOnReadyListener(new TranslationAudioPlayer.OnReadyListener() {
            @Override
            public void onReady() {
                if (!mEnabled || getPlayer() == null) return;
                mPreviousVolume = getPlayer().getVolume();
                mPreferenceAtDuck = getPlayerData().getPlayerVolume();
                mDucked = true;
                mCurrentVolume = mPreviousVolume;
                mSpeechUntil = 0;
                Utils.postDelayed(mVolumeTick, 50);
                sync();
                Utils.postDelayed(mSync, 1000);
                MessageHelpers.showMessage(getContext(), R.string.vot_playing);
            }

            @Override
            public void onError(String message) {
                stop();
                MessageHelpers.showMessage(getContext(), R.string.vot_unavailable);
            }
        });
        mAudio.play(url, player.getPositionMs(), 1f, player.getSpeed(), player.isPlaying());
    }

    private void applyDuck() {
        if (mDucked && getPlayer() != null) {
            if (getPlayerData().getPlayerVolume() != mPreferenceAtDuck) {
                mPreviousVolume = preferredVolume();
                mPreferenceAtDuck = getPlayerData().getPlayerVolume();
            }
            updateVolume();
        }
    }

    private void updateVolume() {
        if (!mDucked || mAudio == null || getPlayer() == null) return;
        if (getPlayerData().getPlayerVolume() != mPreferenceAtDuck) {
            mPreviousVolume = preferredVolume();
            mPreferenceAtDuck = getPlayerData().getPlayerVolume();
        }
        long now = SystemClock.elapsedRealtime();
        if (mAudio.isReady() && getPlayer().isPlaying() && mAudio.getSpeechRms() >= 0.012) {
            mSpeechUntil = now + 520;
        }
        float target = now < mSpeechUntil ? Math.min(mPreviousVolume, ORIGINAL_VOLUME) : mPreviousVolume;
        float step = target < mCurrentVolume ? 0.45f : 0.083f;
        mCurrentVolume += (target - mCurrentVolume) * step;
        if (Math.abs(target - mCurrentVolume) < 0.002f) mCurrentVolume = target;
        getPlayer().setVolume(mCurrentVolume);
    }

    private float preferredVolume() {
        float volume = getPlayerData().getPlayerVolume();
        Video video = getVideo();
        if (video != null) {
            if (getPlayerTweaksData().isPlayerAutoVolumeEnabled()) {
                volume = volume < 1f ? volume * video.volume : video.volume;
            }
            if (video.isShorts) volume /= 2f;
        }
        return volume;
    }

    private void sync() {
        PlaybackView player = getPlayer();
        if (player == null || mAudio == null || !mAudio.isReady()) return;
        if (player.isPlaying()) mAudio.resume(); else mAudio.pause();
        long position = player.getPositionMs();
        if (Math.abs(mAudio.getPositionMs() - position) > 750) mAudio.seekTo(position);
    }

    private void stop() {
        mEnabled = false;
        ++mGeneration;
        Utils.removeCallbacks(mSync);
        Utils.removeCallbacks(mVolumeTick);
        if (mRequest != null) { mRequest.dispose(); mRequest = null; }
        if (mAudio != null) { mAudio.release(); mAudio = null; }
        if (mDucked && getPlayer() != null) {
            getPlayer().setVolume(getPlayerData().getPlayerVolume() == mPreferenceAtDuck
                    ? mPreviousVolume : preferredVolume());
        }
        mDucked = false;
        mSpeechUntil = 0;
        setButton(PlayerUI.BUTTON_OFF);
    }

    private void setButton(int state) {
        if (getPlayer() != null) getPlayer().setButtonState(R.id.action_voice_translate, state);
    }

    @Override public void onNewVideo(Video item) { stop(); mSource = null; }
    @Override public void onEngineReleased() { stop(); }
    @Override public void onPlayEnd() { stop(); }
    @Override public void onFinish() { stop(); }
    @Override public void onViewDestroyed() { stop(); }
    @Override public void onViewResumed() { sync(); }
    @Override public void onVideoLoaded(Video item) { applyDuck(); }
    @Override public void onTrackChanged(FormatItem track) { applyDuck(); }
    @Override public void onPlay() { sync(); }
    @Override public void onPause() { if (mAudio != null) mAudio.pause(); }
    @Override public void onBuffering() { if (mAudio != null) mAudio.pause(); }
    @Override public void onSeekEnd() { sync(); }
    @Override public void onSpeedChanged(float speed) { if (mAudio != null) mAudio.setPlaybackSpeed(speed); }
}
