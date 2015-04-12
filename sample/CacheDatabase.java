import java.io.*;
import java.util.HashMap;
import java.rmi.RemoteException;
class CacheDatabase implements Cloud.DatabaseOps, Serializable {
	public HashMap<String, String> cache = new HashMap<String, String>();
	public Cloud.DatabaseOps db; 
	public CacheDatabase(String ip, int port) {
		ServerLib SL = new ServerLib(ip, port);	
		db = SL.getDB();
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
		if (cache.containsKey(key)) {
			cache.put(key, val);
		}
		return db.set(key, val, auth);
	}
	public boolean transaction(String item, float price, int qty) throws RemoteException {
		return db.transaction(item, price, qty);
	}
}
