package com.lody.virtual.server.am;

import com.lody.virtual.helper.collection.ArrayMap;
import com.lody.virtual.helper.collection.SparseArray;
import com.lody.virtual.helper.utils.VLog;

class ProcessMap<E> {
    private final ArrayMap<String, SparseArray<E>> mMap = new ArrayMap<>();

    public E get(String name, int uid) {
        SparseArray<E> uids = mMap.get(name);
        if (uids == null)
            return null;
        return uids.get(uid);
    }

    public E put(String name, int uid, E value) {
        SparseArray<E> uids = mMap.get(name);
        if (uids == null) {
            uids = new SparseArray<E>(2);
            mMap.put(name, uids);
        }
        uids.put(uid, value);
        return value;
    }

    public void removeIfCurrent(String name, int uid, E expected) {
        SparseArray<E> uids = mMap.get(name);
        if (uids != null && uids.get(uid) == expected) {
            uids.remove(uid);
            if (uids.size() == 0) {
                mMap.remove(name);
            }
        }
    }

    public ArrayMap<String, SparseArray<E>> getMap() {
        return mMap;
    }
}
