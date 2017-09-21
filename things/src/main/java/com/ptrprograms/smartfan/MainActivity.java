package com.ptrprograms.smartfan;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.awareness.Awareness;
import com.google.android.gms.awareness.fence.AwarenessFence;
import com.google.android.gms.awareness.fence.FenceState;
import com.google.android.gms.awareness.fence.FenceUpdateRequest;
import com.google.android.gms.awareness.fence.TimeFence;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.things.contrib.driver.button.Button;
import com.google.android.things.pio.Gpio;
import com.google.android.things.pio.PeripheralManagerService;
import com.google.assistant.embedded.v1alpha1.AudioInConfig;
import com.google.assistant.embedded.v1alpha1.AudioOutConfig;
import com.google.assistant.embedded.v1alpha1.ConverseConfig;
import com.google.assistant.embedded.v1alpha1.ConverseRequest;
import com.google.assistant.embedded.v1alpha1.ConverseResponse;
import com.google.assistant.embedded.v1alpha1.ConverseState;
import com.google.assistant.embedded.v1alpha1.EmbeddedAssistantGrpc;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.protobuf.ByteString;

import org.json.JSONException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.TimeZone;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.auth.MoreCallCredentials;
import io.grpc.stub.StreamObserver;

public class MainActivity extends Activity implements Button.OnButtonEventListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private static final String FAN_URL = "https://smart-fan.firebaseio.com/";
    private static final String FAN_STATE_GPIO_PIN = "BCM18";
    private static final String ASSISTANT_BUTTON_GPIO_PIN = "BCM23";

    //Awareness API values
    private static final String TIME_FENCE_KEY = "time_fence_key";
    private final String FENCE_RECEIVER_ACTION = "FENCE_RECEIVER_ACTION";
    private GoogleApiClient googleApiClient;
    private PendingIntent pendingIntent;
    private FenceReceiver fenceReceiver;

    private static final int START_WINDOW_IN_MILLIS = 0;
    private static final int END_WINDOW_IN_MILLIS = 75600000;

    private DatabaseReference databaseRef;
    private Gpio fanStateSignal;
    private SmartFan smartFanStates;
    private Button mButton;

    private static final String ASSISTANT_ENDPOINT = "embeddedassistant.googleapis.com";

    private static final int BUTTON_DEBOUNCE_DELAY_MS = 20;
    private static final String PREF_CURRENT_VOLUME = "current_volume";
    private static final int SAMPLE_RATE = 16000;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int DEFAULT_VOLUME = 100;
    private static final int SAMPLE_BLOCK_SIZE = 1024;

    private AudioTrack mAudioTrack;
    private AudioRecord mAudioRecord;
    private int mVolumePercentage = DEFAULT_VOLUME;

    private ByteString mConversationState = null;
    private HandlerThread mAssistantThread;
    private Handler mAssistantHandler;

    private static AudioInConfig.Encoding ENCODING_INPUT = AudioInConfig.Encoding.LINEAR16;
    private static AudioOutConfig.Encoding ENCODING_OUTPUT = AudioOutConfig.Encoding.LINEAR16;

    private EmbeddedAssistantGrpc.EmbeddedAssistantStub mAssistantService;
    private StreamObserver<ConverseRequest> mAssistantRequestObserver;

    private static final AudioFormat AUDIO_FORMAT_STEREO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();

    private static final AudioFormat AUDIO_FORMAT_OUT_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();

    private static final AudioFormat AUDIO_FORMAT_IN_MONO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();

    private StreamObserver<ConverseResponse> mAssistantResponseObserver =
            new StreamObserver<ConverseResponse>() {
                @Override
                public void onNext(ConverseResponse value) {
                    switch (value.getConverseResponseCase()) {
                        case EVENT_TYPE:
                            break;
                        case RESULT:
                            mConversationState = value.getResult().getConversationState();
                            if (value.getResult().getVolumePercentage() != 0) {
                                mVolumePercentage = value.getResult().getVolumePercentage();
                                float newVolume = AudioTrack.getMaxVolume() * mVolumePercentage / 100.0f;
                                mAudioTrack.setVolume(newVolume);
                                SharedPreferences.Editor editor = PreferenceManager.
                                        getDefaultSharedPreferences(MainActivity.this).edit();
                                editor.putFloat(PREF_CURRENT_VOLUME, newVolume);
                                editor.apply();
                            }
                            break;
                        case AUDIO_OUT:
                            final ByteBuffer audioData =
                                    ByteBuffer.wrap(value.getAudioOut().getAudioData().toByteArray());
                            mAudioTrack.write(audioData, audioData.remaining(), AudioTrack.WRITE_BLOCKING);
                            break;
                        case ERROR:
                            break;
                    }
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {}
            };

    private Runnable mStartAssistantRequest = new Runnable() {
        @Override
        public void run() {
            mAudioRecord.startRecording();
            mAssistantRequestObserver = mAssistantService.converse(mAssistantResponseObserver);
            ConverseConfig.Builder converseConfigBuilder =
                    ConverseConfig.newBuilder()
                            .setAudioInConfig(AudioInConfig.newBuilder()
                                    .setEncoding(ENCODING_INPUT)
                                    .setSampleRateHertz(SAMPLE_RATE)
                                    .build())
                            .setAudioOutConfig(AudioOutConfig.newBuilder()
                                    .setEncoding(ENCODING_OUTPUT)
                                    .setSampleRateHertz(SAMPLE_RATE)
                                    .setVolumePercentage(mVolumePercentage)
                                    .build());
            if (mConversationState != null) {
                converseConfigBuilder.setConverseState(
                        ConverseState.newBuilder()
                                .setConversationState(mConversationState)
                                .build());
            }

            mAssistantRequestObserver.onNext(ConverseRequest.newBuilder()
                    .setConfig(converseConfigBuilder.build())
                    .build());
            mAssistantHandler.post(mStreamAssistantRequest);
        }
    };
    private Runnable mStreamAssistantRequest = new Runnable() {
        @Override
        public void run() {
            ByteBuffer audioData = ByteBuffer.allocateDirect(SAMPLE_BLOCK_SIZE);
            int result =
                    mAudioRecord.read(audioData, audioData.capacity(), AudioRecord.READ_BLOCKING);
            if (result <= 0) {
                return;
            }
            mAssistantRequestObserver.onNext(ConverseRequest.newBuilder()
                    .setAudioIn(ByteString.copyFrom(audioData))
                    .build());
            mAssistantHandler.post(mStreamAssistantRequest);
        }
    };
    private Runnable mStopAssistantRequest = new Runnable() {
        @Override
        public void run() {
            mAssistantHandler.removeCallbacks(mStreamAssistantRequest);
            if (mAssistantRequestObserver != null) {
                mAssistantRequestObserver.onCompleted();
                mAssistantRequestObserver = null;
            }
            mAudioRecord.stop();
            mAudioTrack.play();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initAssistantThread();
        initAssistantButton();
        initAudio();

        initFirebase();
        initFanSignals();
        initPlayServices();
    }

    private void initPlayServices() {
        Log.e("Test", "initplayservices");
        googleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Awareness.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
    }

    @Override
    protected void onStop() {
        Awareness.FenceApi.updateFences(
                googleApiClient,
                new FenceUpdateRequest.Builder()
                        .removeFence(TIME_FENCE_KEY)
                        .build());

        googleApiClient.disconnect();
        if (fenceReceiver != null) {
            unregisterReceiver(fenceReceiver);
        }

        super.onStop();
    }

    private void initAssistantThread() {
        mAssistantThread = new HandlerThread("assistantThread");
        mAssistantThread.start();
        mAssistantHandler = new Handler(mAssistantThread.getLooper());
    }

    private void initAudio() {
        AudioManager manager = (AudioManager)this.getSystemService(Context.AUDIO_SERVICE);
        int maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, mVolumePercentage * maxVolume / 100, 0);
        int outputBufferSize = AudioTrack.getMinBufferSize(AUDIO_FORMAT_OUT_MONO.getSampleRate(),
                AUDIO_FORMAT_OUT_MONO.getChannelMask(),
                AUDIO_FORMAT_OUT_MONO.getEncoding());
        mAudioTrack = new AudioTrack.Builder()
                .setAudioFormat(AUDIO_FORMAT_OUT_MONO)
                .setBufferSizeInBytes(outputBufferSize)
                .build();
        mAudioTrack.play();
        int inputBufferSize = AudioRecord.getMinBufferSize(AUDIO_FORMAT_STEREO.getSampleRate(),
                AUDIO_FORMAT_STEREO.getChannelMask(),
                AUDIO_FORMAT_STEREO.getEncoding());
        mAudioRecord = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AUDIO_FORMAT_IN_MONO)
                .setBufferSizeInBytes(inputBufferSize)
                .build();

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        float initVolume = preferences.getFloat(PREF_CURRENT_VOLUME, maxVolume);
        mAudioTrack.setVolume(initVolume);

        mVolumePercentage = Math.round(initVolume * 100.0f / maxVolume);

        ManagedChannel channel = ManagedChannelBuilder.forTarget(ASSISTANT_ENDPOINT).build();
        try {
            mAssistantService = EmbeddedAssistantGrpc.newStub(channel)
                    .withCallCredentials(MoreCallCredentials.from(
                            Credentials.fromResource(this, R.raw.credentials)
                    ));
        } catch (IOException|JSONException e) {}
    }

    private void initFirebase() {
        databaseRef = FirebaseDatabase.getInstance().getReferenceFromUrl(FAN_URL);
        databaseRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {

                smartFanStates = dataSnapshot.getValue(SmartFan.class);

                try {
                    fanStateSignal.setValue(smartFanStates.isFanOn());
                } catch( IOException e ) {

                }

                if( !googleApiClient.isConnected() && !googleApiClient.isConnecting() ) {
                    googleApiClient.connect();
                }

            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    private void initAssistantButton() {
        try {
            mButton = new Button(ASSISTANT_BUTTON_GPIO_PIN, Button.LogicState.PRESSED_WHEN_HIGH);
            mButton.setDebounceDelay(BUTTON_DEBOUNCE_DELAY_MS);
            mButton.setOnButtonEventListener(this);
        } catch (IOException e) {
            return;
        }
    }

    private void initFanSignals() {
        PeripheralManagerService service = new PeripheralManagerService();

        try {
            fanStateSignal = service.openGpio(FAN_STATE_GPIO_PIN);
            fanStateSignal.setDirection(Gpio.DIRECTION_OUT_INITIALLY_LOW);
        } catch( IOException e ) {

        }
    }

    @Override
    public void onButtonEvent(Button button, boolean pressed) {
        if (pressed) {
            mAssistantHandler.post(mStartAssistantRequest);
        } else {
            mAssistantHandler.post(mStopAssistantRequest);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if( fanStateSignal != null ) {
            try {
                fanStateSignal.setValue(false);
                fanStateSignal.close();
                fanStateSignal = null;
            } catch( IOException e ) {

            }
        }

        if (mAudioRecord != null) {
            mAudioRecord.stop();
            mAudioRecord = null;
        }
        if (mAudioTrack != null) {
            mAudioTrack.stop();
            mAudioTrack = null;
        }
        if (mButton != null) {
            try {
                mButton.close();
            } catch (IOException e) {}
            mButton = null;
        }

        mAssistantHandler.post(new Runnable() {
            @Override
            public void run() {
                mAssistantHandler.removeCallbacks(mStreamAssistantRequest);
            }
        });
        mAssistantThread.quitSafely();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.e("Test", "onconnected");
        Intent intent = new Intent(FENCE_RECEIVER_ACTION);
        pendingIntent =
                PendingIntent.getBroadcast(MainActivity.this, 0, intent, 0);

        fenceReceiver = new FenceReceiver();
        registerReceiver(fenceReceiver, new IntentFilter(FENCE_RECEIVER_ACTION));
        setupFences();
    }

    private void turnOnFan() {
        smartFanStates.setFanOn(true);
        databaseRef.setValue(smartFanStates);

        try {
            fanStateSignal.setValue(true);
        } catch( IOException e ) {

        }
    }

    @SuppressWarnings({"MissingPermission"})
    private void setupFences() {
        AwarenessFence timeFence = TimeFence.inDailyInterval(TimeZone.getDefault(), START_WINDOW_IN_MILLIS, END_WINDOW_IN_MILLIS);

        Awareness.FenceApi.updateFences(
                googleApiClient,
                new FenceUpdateRequest.Builder()
                        .addFence(TIME_FENCE_KEY, timeFence, pendingIntent)
                        .build());
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        Log.e("Test", "onconnectionfailed: " + connectionResult.getErrorCode());
    }

    public class FenceReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            FenceState fenceState = FenceState.extract(intent);
            if (TextUtils.equals(fenceState.getFenceKey(), TIME_FENCE_KEY)) {
                if( fenceState.getCurrentState() == FenceState.TRUE && (smartFanStates != null && smartFanStates.isAutoOn())) {
                    turnOnFan();
                }
            }
        }
    }

    @Override
    public void onConnectionSuspended(int i) {

    }
}
