package com.lody.virtual.server.pm;

import android.content.pm.PackageManager;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * @author Lody
 */
public class ComponentState implements Parcelable {

    public int state = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(this.state);
    }

    public ComponentState() {
    }

    protected ComponentState(Parcel in) {
        int stored = in.readInt();
        // Older containers accepted application-only and invalid component states.
        // Preserve real choices; an unsupported legacy override means no override.
        this.state = stored >= PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                && stored <= PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                ? stored : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    }

    public static final Creator<ComponentState> CREATOR = new Creator<ComponentState>() {
        @Override
        public ComponentState createFromParcel(Parcel source) {
            return new ComponentState(source);
        }

        @Override
        public ComponentState[] newArray(int size) {
            return new ComponentState[size];
        }
    };
}
