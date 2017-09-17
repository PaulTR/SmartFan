package com.ptrprograms.smartfan;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class MainActivity extends AppCompatActivity {

    private static final String FAN_URL = "https://smart-fan.firebaseio.com/";
    private Button fanStateButton;
    private Button autoOnFanStateButton;
    private DatabaseReference databaseRef;
    private SmartFan smartFan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        databaseRef = FirebaseDatabase.getInstance().getReferenceFromUrl(FAN_URL);
        databaseRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                smartFan = dataSnapshot.getValue(SmartFan.class);
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });

        fanStateButton = (Button) findViewById(R.id.fan_state);
        fanStateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                smartFan.setFanOn(!smartFan.isFanOn());
                databaseRef.setValue(smartFan);
            }
        });

        autoOnFanStateButton = (Button) findViewById(R.id.auto_on_state);
        autoOnFanStateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                smartFan.setAutoOn(!smartFan.isAutoOn());
                databaseRef.setValue(smartFan);
            }
        });



    }
}
