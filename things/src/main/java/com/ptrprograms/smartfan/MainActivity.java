package com.ptrprograms.smartfan;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.os.Bundle;
import android.os.Handler;
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
import com.google.android.things.pio.UartDevice;
import com.google.android.things.pio.UartDeviceCallback;
import com.google.assistant.embedded.v1alpha1.ConverseResponse;
import com.google.auth.oauth2.UserCredentials;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.rpc.Status;

import org.json.JSONException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.TimeZone;

import static android.content.ContentValues.TAG;

public class MainActivity extends Activity implements Button.OnButtonEventListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private static final char LED_RING_CODE_CONVO_START = 'S';
    private static final char LED_RING_CODE_OFF = 'F';
    private static final char LED_RING_CODE_ON = 'O';

    private static final String FAN_URL = "https://smart-fan.firebaseio.com/";
    private static final String FAN_STATE_GPIO_PIN = "BCM18";
    private static final String ASSISTANT_BUTTON_GPIO_PIN = "BCM23";

    // Audio constants.
    private static final String PREF_CURRENT_VOLUME = "current_volume";
    private static final int SAMPLE_RATE = 16000;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int DEFAULT_VOLUME = 100;

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
    private UartDevice arduinoDevice;

    // Peripheral and drivers constants.
    private static final int BUTTON_DEBOUNCE_DELAY_MS = 20;

    private static final AudioFormat AUDIO_FORMAT_STEREO =
            new AudioFormat.Builder()
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .setEncoding(ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .build();

    private Handler mMainHandler;

    // List & adapter to store and display the history of Assistant Requests.
    private EmbeddedAssistant mEmbeddedAssistant;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initArduino();
        initAssistantButton();
        initAudio();
        initFirebase();
        initFanSignals();
        initPlayServices();
    }

    private void writeCodeToArduino(char code) {
        try {
            byte[] data = new byte[1];
            data[0] = (byte) code;
            arduinoDevice.write(data, data.length);
        } catch(IOException e) {
            Log.e("Test", "exception writing to Arduino");
        }
    }

    private void initArduino() {
        PeripheralManagerService service = new PeripheralManagerService();
        try {
            arduinoDevice = service.openUartDevice("UART0");
            arduinoDevice.setBaudrate(9600);
            arduinoDevice.setDataSize(8);
            arduinoDevice.setParity(UartDevice.PARITY_NONE);
            arduinoDevice.setStopBits(1);

            arduinoDevice.registerUartDeviceCallback(new UartDeviceCallback() {
                @Override
                public boolean onUartDeviceDataAvailable(UartDevice uart) {
                    byte[] buffer = new byte[1];
                    try {
                        uart.read(buffer, 1);
                        Log.e("Test", "received code: " + new String(buffer, Charset.forName("UTF-8")));
                    } catch( IOException e ) {

                    }
                    return super.onUartDeviceDataAvailable(uart);
                }
            });
        } catch( IOException e ) {
            Log.e("Test", "error on initializing arduino");
        }
    }

    private void initPlayServices() {
        Log.e("Test", "initplayservices");
        googleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Awareness.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
    }

    private void initAudio() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        int initVolume = preferences.getInt(PREF_CURRENT_VOLUME, DEFAULT_VOLUME);

        UserCredentials userCredentials = null;
        try {
            userCredentials =
                    EmbeddedAssistant.generateCredentials(this, R.raw.credentials);
        } catch (IOException | JSONException e) {
        }
        mEmbeddedAssistant = new EmbeddedAssistant.Builder()
                .setCredentials(userCredentials)
                .setAudioSampleRate(SAMPLE_RATE)
                .setAudioVolume(initVolume)
                .setRequestCallback(new EmbeddedAssistant.RequestCallback() {
                    @Override
                    public void onRequestStart() {
                        writeCodeToArduino(LED_RING_CODE_CONVO_START);
                    }

                    @Override
                    public void onSpeechRecognition(String utterance) {

                    }
                })
                .setConversationCallback(new EmbeddedAssistant.ConversationCallback() {
                    @Override
                    public void onConversationEvent(ConverseResponse.EventType eventType) {
                        Log.d(TAG, "converse response event: " + eventType);
                    }

                    @Override
                    public void onAudioSample(ByteBuffer audioSample) {

                    }

                    @Override
                    public void onConversationError(Status error) {
                        Log.e(TAG, "converse response error: " + error);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        Log.e(TAG, "converse error:", throwable);
                    }

                    @Override
                    public void onVolumeChanged(int percentage) {
                        Log.i(TAG, "assistant volume changed: " + percentage);
                        // Update our shared preferences
                        SharedPreferences.Editor editor = PreferenceManager
                                .getDefaultSharedPreferences(MainActivity.this)
                                .edit();
                        editor.putInt(PREF_CURRENT_VOLUME, percentage);
                        editor.apply();
                    }

                    @Override
                    public void onConversationFinished() {
                        if( smartFanStates.isFanOn() ) {
                            writeCodeToArduino(LED_RING_CODE_ON);
                        } else {
                            writeCodeToArduino(LED_RING_CODE_OFF);
                        }
                    }
                })
                .build();

        mEmbeddedAssistant.connect();
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

    private void initFirebase() {
        databaseRef = FirebaseDatabase.getInstance().getReferenceFromUrl(FAN_URL);
        databaseRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {

                smartFanStates = dataSnapshot.getValue(SmartFan.class);

                try {
                    fanStateSignal.setValue(smartFanStates.isFanOn());
                    if( smartFanStates.isFanOn() ) {
                        writeCodeToArduino('O');
                    } else {
                        writeCodeToArduino('Q');
                    }
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
        Log.e("Test", "button: " + pressed);
        if (pressed) {
            mEmbeddedAssistant.startConversation();
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

        if (mButton != null) {
            try {
                mButton.close();
            } catch (IOException e) {}
            mButton = null;
        }

        mEmbeddedAssistant.destroy();

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
