package com.ptrprograms.smartfan;

public class SmartFan {

    private boolean fanOn;
    private boolean autoOn;

    public SmartFan() {

    }

    public SmartFan(boolean fanOn, boolean autoOn) {
        this.fanOn = fanOn;
        this.autoOn = autoOn;
    }


    public boolean isAutoOn() {
        return autoOn;
    }

    public void setAutoOn(boolean autoOn) {
        this.autoOn = autoOn;
    }

    public boolean isFanOn() {
        return fanOn;
    }

    public void setFanOn(boolean fan_state) {
        this.fanOn = fan_state;
    }
}
