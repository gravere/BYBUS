package com.lody.virtual.client.ipc;

import android.os.DeadObjectException;
import android.os.RemoteException;

/** Exactly one reacquisition for explicitly idempotent reads, never for a mutation. */
public final class DeadServiceQuery {
    private DeadServiceQuery() { }
    public interface Query<T> { T run() throws RemoteException; }

    public static <T> T run(Query<T> query, Runnable invalidate) throws RemoteException {
        try { return query.run(); }
        catch (DeadObjectException dead) {
            invalidate.run();
            return query.run();
        }
    }

    public static <T> T requireService(T service) throws DeadObjectException {
        if (service == null) throw new DeadObjectException();
        return service;
    }
}
