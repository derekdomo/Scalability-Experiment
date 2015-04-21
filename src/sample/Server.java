import java.io.*;
import java.rmi.Naming;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.LocateRegistry;
import java.rmi.NoSuchObjectException;
import java.rmi.RemoteException;

interface ChatServer extends Remote{
    public void initiateMaster(String ip, int port) throws RemoteException;
    public void initiateSlave(String ip, int port, Role role) throws RemoteException;
    public Role getRole() throws RemoteException;
	public void setRole(Role role) throws RemoteException;
    public void closeRole() throws RemoteException;
    public int getLength() throws RemoteException;
    public void storeRequest(RequestPack request) throws RemoteException;
    public RequestPack getRequest() throws RemoteException;
	public void setFlag() throws RemoteException;
	public Cloud.DatabaseOps getDatabase() throws RemoteException;
	public int getServerLibLength() throws RemoteException;
	public void dropHead() throws RemoteException;	
}

public class Server extends UnicastRemoteObject implements ChatServer{ 
	public Server() throws RemoteException{
		super();
    }
	public static LinkedList<Role> roles = new LinkedList<Role>();
    public static ArrayList<Role> frontTier = new ArrayList<Role>();
    public static ArrayList<Role> midTier= new ArrayList<Role>();
    public static ServerLib SL;
    public static Role role;
    public static LinkedList<RequestPack> requests= new LinkedList<RequestPack>();
    public static int threshHoldAvg = 2;
	public static boolean startNotification = false; 
	public static ChatServer server; 
	public static boolean flagShutDown = false;
	public static Cloud.DatabaseOps cache;
	public static int frontSize;
	public static int midSize;
	public static long adam;

 	public void initiateMaster(String ip, int port) {
		try{
			String s = "master";
			Naming.rebind(String.format("//%s:%d/%s",ip, port, s), this);
		} catch(Exception err) {
			System.out.println(err.getMessage());
		}
	}
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
        Role ret = roles.pollFirst();
        return ret;
	}
	// Method for Slaves to register their roles
	public void setRole(Role role) {
        if (role instanceof RoleFrontTier)
            frontTier.add(role);
        else if (role instanceof RoleMidTier) {
        	midTier.add(role);
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

    public int getLength() {
    	return requests.size();
    }

	public int getServerLibLength() {
		return SL.getQueueLength();	
	}
	
	public void dropHead() {
		while (SL.getQueueLength()>1)
			SL.dropHead();
	} 

    /*
    * RMI Methods for Front Tier
    * */
    // Methods for the front tier VM to call the leader 
    public void storeRequest(RequestPack request) {
    	requests.add(request);
    }
	// Method for Front tier to notify completion
	public void setFlag() {
		startNotification = true;
	}

    /*
    * RMI Methods for Mid Tier
    * */
    // Methods for Mid Tier to get request
    public RequestPack getRequest() {
    	return requests.poll();
    }
	
	public Cloud.DatabaseOps getDatabase() {
		return this.cache;
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
	
	public static void main ( String args[] ) throws Exception {
		adam = System.currentTimeMillis();	
		if (args.length != 2) throw new Exception("Need 2 args: <cloud_ip> <cloud_port>");
        String ip = args[0];
        int port = Integer.parseInt(args[1]);

        server = new Server();
		SL = new ServerLib( ip, port );
		// register with load balancer so requests are sent to this server
        // Master Node
        // Responsibility:
        //          1. Warm up
        //          2. Scaling up
        //          3. Scaling down
		if (SL.getStatusVM(2)==Cloud.CloudOps.VMStatus.NonExistent) {
			// I am a master
			server.initiateMaster(args[0], Integer.parseInt(args[1]));
            int countForFront = 1;
            int countForMid = 2;
			frontSize = 1;
			midSize = 2;
            // assign countForFront VMs for front tier
            for (int i=0; i<countForFront; i++) {
                Role temp = new RoleFrontTier("frontEnd"+String.valueOf(i));
                roles.add(temp);
            }
            // assign countForMid VMs for middle tier
            for (int i=0; i<countForMid; i++) {
                Role temp = new RoleMidTier("midEnd"+String.valueOf(i));
                roles.add(temp);
            }
			Thread sch = new Thread(new Schedule(ip, port));
			sch.start();
            SL.register_frontend();
			System.out.println("Start recieving request");
			long st = System.currentTimeMillis();
			cache = new CacheDatabase(SL.getDB());
			while (System.currentTimeMillis()-st<4500) {
				SL.dropHead();
			}
            while (true) {
				if (SL.getQueueLength() == 0) {
					Thread.sleep(20);
					continue;
				}
				try {
                	Cloud.FrontEndOps.Request r = SL.getNextRequest();
					RequestPack req = new RequestPack(System.currentTimeMillis(), r);
                	requests.add(req);
				} catch (Exception err){}
            }
		}
        // Slave nodes: real workers
        //          1. FrontTier
        //          2. MidTier
        // For now, slaves can only be mid-tier
        else {
         	// get my role
            ChatServer communicateMaster = null;			
			communicateMaster = getServerInstance(ip, port, "master"); 
			cache = communicateMaster.getDatabase();
            role = communicateMaster.getRole();
            server.initiateSlave(ip, port, role);	
			communicateMaster.setRole(role);
			if (role instanceof RoleFrontTier) {
            	SL.register_frontend();
				System.out.println("Front Tier");
				try {
					while (!flagShutDown) {
						if (SL.getQueueLength() == 0) {
							Thread.sleep(20);
							continue;
						}
                		Cloud.FrontEndOps.Request r = SL.getNextRequest();
						RequestPack req = new RequestPack(System.currentTimeMillis(), r);
                		communicateMaster.storeRequest(req);
					}
				} catch (Exception err){
					System.out.println(err.getMessage());
				}
			} else {
				try{
					System.out.println("Mid Tier");
            		while (!flagShutDown) {
						if (communicateMaster.getLength() == 0) {
							Thread.sleep(20);
							continue;	
						}
						long now = System.currentTimeMillis();
            	    	RequestPack req = communicateMaster.getRequest();
						if (!req.r.isPurchase) {
							if (now-req.timeStamp>750) {
								SL.drop(req.r);
							}
							else
            	    			SL.processRequest(req.r, cache);
						} else {
							if (now-req.timeStamp>1500) {
								SL.drop(req.r);
							}
							else
            	    			SL.processRequest(req.r, cache);
						}
					}
				} catch (Exception err){
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
    public boolean isController() {
        if (nameRegistered.equals("frontEnd0"))
            return true;
        return false;
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
	public int checkFrontTier(HashMap<Role, ChatServer> frontServer) throws Exception {
		int sum = 0;
        for (Role r : frontServer.keySet()) {
			for (ChatServer server:frontServer.values()) 
				sum += server.getServerLibLength();
		}
		return sum+Server.SL.getQueueLength();
	}
	public void run(){
        // Scale for both browse VM and purchase VM
        int curMid = 2;
		int curFront = 1;
		try {
			Server.SL.startVM();
			Server.SL.startVM();
			Server.SL.startVM();
			HashMap<Role, ChatServer> frontServer = new HashMap<Role, ChatServer>();
			Thread.sleep(5000);
			int avgMidLenDown = 0;
			int avgMidLenUp = 0;
			int countDownMid = 0;
			int countDownFront = 0;
			int countUpMid = 0;
			int countUpFront = 0;
			int coolDown = 10000;
			boolean lateScaleDown = false;
			long st = System.currentTimeMillis();
			long st2 = 0;
    		while (true) {
				for (Role role:Server.frontTier) {
					if (!frontServer.containsKey(role)) {
						ChatServer temp = null; 
						temp = Server.getServerInstance(ip, port, role.nameRegistered);
						frontServer.put(role, temp);
					}
				}
				// scale for Front Tier and Mid Tier
				if (countDownMid == 80) {
					if (Server.midSize>2 && avgMidLenDown<Server.midSize*80 && System.currentTimeMillis()-st2>7000) {
						Role temp = Server.midTier.remove(0);
    		        	ChatServer del = Server.getServerInstance(ip, port, temp.nameRegistered);
						Server.midSize -= 1;
    		        	del.closeRole();
                    	System.out.println("Scale down mid end");
					}
					countDownMid = 0;
					avgMidLenDown = 0;
				}
				// scale out for mid tier
				if (countUpMid == 5) {
					if (System.currentTimeMillis()-st2>7000) {
						int numToOpen = avgMidLenUp/5/Server.midSize;
						if (numToOpen != 0) {
							scaleOut(numToOpen*2);
							st2 = System.currentTimeMillis();
						}
					}
					countUpMid = 0;
					avgMidLenUp = 0;
				}
				Thread.sleep(100);
    		    // scale for browse request VM and purchase request VM
				if (!lateScaleDown&&(System.currentTimeMillis()-st)>10000)
					lateScaleDown = true; 
				int cur = checkFrontTier(frontServer);
				int ratio = 1;
    		    if (cur > Server.frontSize+1  && Server.frontSize<3) {
					int diff = cur - Server.frontSize - 1; 
					for (int i=0; i<diff/ratio && Server.frontSize<3; i++) {
    		        	Role temp = new RoleFrontTier("frontEnd"+String.valueOf(curFront++));
						Server.frontSize += 1;
						Server.roles.add(temp);
    		    		Server.SL.startVM();
					}
                    countDownFront = 0;
    		    } else if (lateScaleDown && cur < Server.frontSize){
					countDownFront++;
				}
    		    int obMid = Server.requests.size();
				avgMidLenUp += obMid;
				avgMidLenDown += obMid;
                countUpMid++;
				countDownMid++;
    		}
		} catch(Exception err) {
			err.printStackTrace();
		}
	}
	
	public void scaleOut(int diff) {
		System.out.print("Mid Tier Scaled to\t"+(System.currentTimeMillis()-Server.adam));
		System.out.println(diff);
		for (int i=0; i<diff && Server.midSize < 10; i++) {
    		Role temp = new RoleMidTier("midEnd"+String.valueOf(Server.midSize++));
			Server.roles.add(temp);
    		Server.SL.startVM();
		}
	}
}
