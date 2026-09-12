package com.miko.app_update.update;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;

/* JADX INFO: loaded from: classes.dex */
public class farrayCopy {

    @SerializedName("commands")
    ArrayList<String> commands;

    @SerializedName("files")
    ArrayList<fileCopy> files;

    public ArrayList<String> getCommands() {
        return this.commands;
    }

    public void setCommands(ArrayList<String> arrayList) {
        this.commands = arrayList;
    }

    public ArrayList<fileCopy> getFiles() {
        return this.files;
    }

    public void setFiles(ArrayList<fileCopy> arrayList) {
        this.files = arrayList;
    }
}
