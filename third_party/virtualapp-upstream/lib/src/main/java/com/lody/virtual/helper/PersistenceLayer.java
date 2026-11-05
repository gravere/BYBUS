package com.lody.virtual.helper;

import android.os.Parcel;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * @author Lody
 */
public abstract class PersistenceLayer {

    private File mPersistenceFile;

    public PersistenceLayer(File persistenceFile) {
        this.mPersistenceFile = persistenceFile;
    }

    public final File getPersistenceFile() {
        return mPersistenceFile;
    }

    public abstract int getCurrentVersion();

    public void writeMagic(Parcel p) {
    }

    public boolean verifyMagic(Parcel p) {
        return true;
    }

    public abstract void writePersistenceData(Parcel p);

    public abstract void readPersistenceData(Parcel p, int version);

    public void onPersistenceFileDamage() {
    }

    public void save() {
        Parcel p = Parcel.obtain();
        try {
            writeMagic(p);
            p.writeInt(getCurrentVersion());
            writePersistenceData(p);
            com.lody.virtual.helper.utils.DurableFile.write(mPersistenceFile, p.marshall());
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            p.recycle();
        }
    }

    public void read() {
        File file = mPersistenceFile;
        if (!file.exists()) return;
        Parcel p = Parcel.obtain();
        try {
            byte[] bytes = com.lody.virtual.helper.utils.DurableFile.read(file, 16 * 1024 * 1024);
            p.unmarshall(bytes, 0, bytes.length);
            p.setDataPosition(0);
            if (!verifyMagic(p)) {
                onPersistenceFileDamage();
                throw new IOException("Invalid persistence file.");
            }
            int fileVersion = p.readInt();
            readPersistenceData(p, fileVersion);
        } catch (Exception e) {
            if (!(e instanceof FileNotFoundException)) {
                e.printStackTrace();
            }
        } finally {
            p.recycle();
        }
    }
}
