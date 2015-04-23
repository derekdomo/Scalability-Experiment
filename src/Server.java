import java.io.Serializable;
import java.rmi.Naming;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingDeque;

interface ChatServer extends Remote{
    public void initiateMaster(String ip, int port) throws RemoteException;
    public void initiateSlave(String ip, int port, Role role) throws RemoteException;
    public void initiateDatabase(String ip, int port) throws RemoteException;
    public Role getRole() throws RemoteException;
	public void setRole(Role role) throws RemoteException;
    public void closeRole() throws RemoteException;
    public void storeRequest(RequestPack request) throws RemoteException;
    public RequestPack getRequest() throws RemoteException;
	public int getRPS() throws RemoteException;
}

public class Server extends UnicastRemoteObject implements ChatServer, Cloud.DatabaseOps{ 
	public Server() throws RemoteException{
		super();
    }
	public static LinkedBlockingDeque<Role> roles = new LinkedBlockingDeque<Role>();
    public static ArrayList<Role> frontTier = new ArrayList<Role>();
    public static ArrayList<Role> midTier= new ArrayList<Role>();
    public static ServerLib SL;
    public static Role role;
    public static LinkedBlockingDeque<RequestPack> requests= new LinkedBlockingDeque<RequestPack>();
	public static HashMap<String, String> cache = new HashMap<String, String>();
	public static ChatServer server; 
	public static boolean flagShutDown = false;
	public static Cloud.DatabaseOps db;
    public static int frontSize;
	public static int midSize;
	public static long adam;
	public static int rps;

	/*
 	*	RMI for Database	
 	* 	*/
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
    // get Database RMI


    /*
    * RMI for Master
    * */
    // Method for Master to initiate itself
    public void initiateMaster(String ip, int port) {
		try{
			String s = "master";
			Naming.rebind(String.format("//%s:%d/%s",ip, port, s), this);
		} catch(Exception err) {
			System.out.println(err.getMessage());
		}
	}
    public void initiateDatabase(String ip, int port) {
        try{
            String s = "database";
            Naming.rebind(String.format("//%s:%d/%s",ip, port, s), this);
        } catch(Exception err) {
            System.out.println(err.getMessage());
        }
    }
    // Method for Master to call to let Slaves to close
    public void closeRole() {
        SL.shutDown();
        try {
            UnicastRemoteObject.unexportObject(server, true);
            server = null;
        } catch(Exception err) {
            err.printStackTrace();
        }
        flagShutDown = true;
    }
    // Method for Master to check Front Tier queue length
    public int getRPS() {
		int ret = rps;
		rps = 0;
        return ret;
    }

    /*
    * RMI for Slave
    * */
    // Method for Slave to initiate itself
    public void initiateSlave(String ip, int port, Role role) {
        try {
            String s = role.nameRegistered;
            Naming.rebind(String.format("//%s:%d/%s",ip, port, s), this);
        } catch(Exception err) {
            System.out.println(err.getMessage());
        }
    }
    // Method for Slaves to call Master to identify his role
	public Role getRole() {
        try {
		    return roles.take();
        } catch (Exception err) {
            err.printStackTrace();
            return null;
        }
	}
	// Method for Slaves to register their roles
	public void setRole(Role role) {
        if (role instanceof RoleFrontTier)
            frontTier.add(role);
        else if (role instanceof RoleMidTier) {
        	midTier.add(role);
        }
	}

    /*
    * RMI Methods for Front Tier
    * */
    // Methods for the front tier VM to call the leader 
    public void storeRequest(RequestPack request) {
        requests.add(request);
    }

    /*
    * RMI Methods for Mid Tier
    * */
    // Methods for Mid Tier to get request
    public RequestPack getRequest() {
        try{
		    return requests.takeFirst();
        } catch(InterruptedException err) {
            err.printStackTrace();
            return null;
        }
    }

    // Method for Slaves to get RMI
    public static ChatServer getServerInstance(String ip, int port, String name) {
        String url = String.format("//%s:%d/%s", ip, port, name);
        try {
            return (ChatServer) Naming.lookup(url);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // Method for Slaves to get RMI
    public static Cloud.DatabaseOps getDatabase(String ip, int port) {
        String url = String.format("//%s:%d/%s", ip, port, "database");
        try {
            return (Cloud.DatabaseOps) Naming.lookup(url);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean addRequest(Cloud.FrontEndOps.Request r) {
        if (r == null)
            return true;
        RequestPack req = new RequestPack(System.currentTimeMillis(), r);
        requests.add(req);
        return true;
    }
    public static boolean addRequestToMaster(Cloud.FrontEndOps.Request r, ChatServer master) {
        if (r == null)
            return true;
        try {
			rps+=1;
            RequestPack req = new RequestPack(System.currentTimeMillis(), r);
            master.storeRequest(req);}
        catch(Exception err){
			err.printStackTrace();
		}
        return true;
    }
	public static boolean processRequest(RequestPack req, Cloud.DatabaseOps cache) throws Exception {
		if (req == null) {
			Thread.sleep(20);
			return true;
		}
		long t = System.currentTimeMillis();
        if (!req.r.isPurchase) {
            if (t - req.timeStamp > 790)
                SL.drop(req.r);
            else
                SL.processRequest(req.r, cache);
        } else {
            if (t - req.timeStamp > 1600)
                SL.drop(req.r);
            else
                SL.processRequest(req.r, cache);
        }
        return true;
    }
	public static void main ( String args[] ) throws Exception {
		adam = System.currentTimeMillis();	
		if (args.length != 2) throw new Exception("Need 2 args: <cloud_ip> <cloud_port>");
        String ip = args[0];
        int port = Integer.parseInt(args[1]);

        server = new Server();
		SL = new ServerLib( ip, port );
		/*
            Register with load balancer so requests are sent to this server
            Master Node
            Responsibility:
                  1. Warm up
                  2. Scaling up
                  3. Scaling down
        */
		if (SL.getStatusVM(2)==Cloud.CloudOps.VMStatus.NonExistent) {
			// I am a master
			server.initiateMaster(ip, port);
            server.initiateDatabase(ip, port);
			frontSize = 1;
			midSize = 2;
            // assign countForFront VMs for front tier
            for (int i=0; i<frontSize; i++) {
                Role temp = new RoleFrontTier("frontEnd"+String.valueOf(i));
                roles.add(temp);
            }
            // assign countForMid VMs for middle tier
            for (int i=0; i<midSize; i++) {
                Role temp = new RoleMidTier("midEnd"+String.valueOf(i));
                roles.add(temp);
            }
            // Thread to manage scaling up and down
			Thread sch = new Thread(new Schedule(ip, port));
			sch.start();
            // Register front end
            SL.register_frontend();
			long st = System.currentTimeMillis();
			db = SL.getDB();
            // Drop the first 5 seconds' requests because of no VM can be started
			while (System.currentTimeMillis()-st<4300) {
				rps ++;
				Cloud.FrontEndOps.Request r = SL.getNextRequest();
				SL.drop(r);
			}
            while (addRequest(SL.getNextRequest())) {
            }
        }
        // Slave nodes: real workers
        //          1. FrontTier
        //          2. MidTier
        // For now, slaves can only be mid-tier
        else {
         	// get my role
            ChatServer communicateMaster = getServerInstance(ip, port, "master");
            role = communicateMaster.getRole();
            server.initiateSlave(ip, port, role);	
			communicateMaster.setRole(role);
			if (role instanceof RoleFrontTier) {
            	SL.register_frontend();
				System.out.println("Front Tier");
				try {
					while (addRequestToMaster(SL.getNextRequest(), communicateMaster)) {
                		if (flagShutDown)
                            break;
					}
				} catch (Exception err){
					System.out.println(err.getMessage());
				}
			} else {
                // Mid Tier
                Cloud.DatabaseOps cacheDB = getDatabase(ip, port);
				try{
					System.out.println("Mid Tier");
                    while (processRequest(communicateMaster.getRequest(), cacheDB)) {
                        if (flagShutDown)
                            break;
					}
				} catch (Exception err){
					err.printStackTrace();
					System.out.println(err.getMessage());
				}
			}
        }
    }
}
class RequestPack implements Serializable{
	long timeStamp;
	Cloud.FrontEndOps.Request r;
	RequestPack(long timeStamp, Cloud.FrontEndOps.Request r) {
		this.r = r;
		this.timeStamp = timeStamp;
	}
}

abstract class Role implements Serializable{
    String nameRegistered;
}
class RoleFrontTier extends Role {
    public RoleFrontTier(String nameRegistered) {
        this.nameRegistered = nameRegistered;
    }
}
class RoleMidTier extends Role {
    public RoleMidTier(String nameRegistered) {
        this.nameRegistered = nameRegistered;
    }
}

class Schedule implements Runnable {
	public String ip;
	public int port;
	public Schedule(String ip, int port) {
		this.ip = ip;
		this.port = port;
	}
	public void run(){
        // Scale for both browse VM and purchase VM
		try {
			Server.SL.startVM();
			Server.SL.startVM();
			Server.SL.startVM();
			HashMap<Role, ChatServer> frontServer = new HashMap<Role, ChatServer>();
			int RPS = 0;
			//int countDownFront = 0;
			int countDown = 0;
			int coolDown = 28000;
			boolean lateScaleDown = false;
			long st_cold_start = System.currentTimeMillis();
			long st_mid_cool_down = 0;
            long st_front_cool_down = 0;
    		while (checkRMIForFront(frontServer)) {
                RPS = checkFrontTier(frontServer)*4;
				//System.out.println("Time\t"+ (System.currentTimeMillis()-Server.adam) + "\t" + RPS*4);
                // One mid tier one second at most handle 3 requests
                int numMidShouldHave = 0;
				if (RPS < 9)
					numMidShouldHave = 2;
				else if (RPS < 11)	
					numMidShouldHave = 4;
				else if (RPS < 16)
					numMidShouldHave = 6;
				else if (RPS < 20)
					numMidShouldHave = 8;
				else
					numMidShouldHave = 10;
                // scale down for Mid Tier
				int numMidShouldOpen = numMidShouldHave - Server.midSize;
                if (numMidShouldOpen > 0) {
					System.out.println("Scale up\t"+RPS);
                    scaleOutMid(numMidShouldOpen);
					countDown = 0;
					st_mid_cool_down = System.currentTimeMillis();
                } else if (System.currentTimeMillis()-st_mid_cool_down>7000 && System.currentTimeMillis()-Server.adam > coolDown){
					countDown ++;
                }
				if (countDown == 8) {
					System.out.println("Scale Down at\t"+(System.currentTimeMillis()-Server.adam));
					scaleDownMid(1);
					countDown = 0;
				}
				Thread.sleep(250);
    		}
		} catch(Exception err) {
			err.printStackTrace();
		}
	}

    public void scaleOutFront(int num) {
        //System.out.print("Front Tier Scaled up\t" + num + "\t");
        //System.out.println(System.currentTimeMillis() - Server.adam);
        for (int i = 0; i < num && Server.frontSize < 4; i++) {
            Role temp = new RoleFrontTier("frontEnd" + String.valueOf(Server.frontSize++));
            Server.roles.add(temp);
            Server.SL.startVM();
        }
    }

    public void scaleOutMid(int num) {
		//System.out.print("Mid Tier Scaled up\t"+num+"\t");
		//System.out.println(System.currentTimeMillis() - Server.adam);
		for (int i=0; i<num && Server.midSize < 10; i++) {
    		Role temp = new RoleMidTier("midEnd"+String.valueOf(Server.midSize++));
			Server.roles.add(temp);
    		Server.SL.startVM();
		}
	}

    public void scaleDownMid(int num) throws Exception {
        //System.out.print("Mid Tier Scaled down\t"+num+"\t");
        //System.out.println(System.currentTimeMillis() - Server.adam);
        for (int i=0; i<num && Server.midSize > 2; i++) {
            Role temp = Server.midTier.remove(0);
			System.out.println(temp.nameRegistered);
            ChatServer del =
                    Server.getServerInstance(ip, port, temp.nameRegistered);
            Server.midSize -= 1;
            del.closeRole();
        }
    }

    public boolean checkRMIForFront(HashMap<Role, ChatServer> frontServer) {
        for (Role role : Server.frontTier) {
            if (!frontServer.containsKey(role)) {
                ChatServer temp =
                        Server.getServerInstance(ip, port, role.nameRegistered);
                frontServer.put(role, temp);
            }
        }
        return true;
    }

    public int checkFrontTier(HashMap<Role, ChatServer> frontServer) throws Exception {
        int sum = 0;
        for (ChatServer server:frontServer.values())
            sum += server.getRPS();
        sum += Server.rps;
		Server.rps=0;
		return sum;
    }
}
