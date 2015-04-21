import java.io.Serializable;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

class CacheDatabase implements Cloud.DatabaseOps, Serializable {
    public ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<String, String>();
    public Cloud.DatabaseOps db;

    public CacheDatabase(Cloud.DatabaseOps db) {
        this.db = db;
    }

    public String get(String key) throws RemoteException {
        if (cache.containsKey(key)) {
            return cache.get(key);
        } else {
            String ret = db.get(key);
            cache.put(key, ret);
            return ret;
        }
    }

    public boolean set(String key, String val, String auth) throws RemoteException {
        return db.set(key, val, auth);
    }

    public boolean transaction(String item, float price, int qty) throws RemoteException {
        return db.transaction(item, price, qty);
    }
}
