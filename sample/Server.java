/* Sample code for basic Server */
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
    public void closeRole() throws RemoteException;
    public int getLength() throws RemoteException;
    public void storeRequest(RequestPack request) throws RemoteException;
    public RequestPack getRequest() throws RemoteException;
	public void setFlag() throws RemoteException;
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
		System.out.println("Instance added");
    }
	// Method for Slaves to call Master to identify his role
	public Role getRole() {
        Role ret = roles.pollFirst();
        if (ret instanceof RoleFrontTier)
            frontTier.add(ret);
        else if (ret instanceof RoleMidTier) {
        	midTier.add(ret);
        }
        return ret;
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


    public static boolean checkLength(ArrayList<Integer> ob, boolean up) {
		if (up) {
       		for (Integer it:ob) {
       	 	    if (it<(midTier.size()+roles.size()))
       	 	    	return false;
       	 	}
			return true;
		} else {
       		for (Integer it:ob) {
       	 	    if (it>(midTier.size()+roles.size()))
       	 	    	return false;
       	 	}
			return true;
		}
    }

	public static void main ( String args[] ) throws Exception {
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
            int countForFront = 0;
            int countForMid = 2;
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
			long start = System.currentTimeMillis();
			while (System.currentTimeMillis()-start<4000) {
            	Cloud.FrontEndOps.Request r = SL.getNextRequest();
				SL.drop(r);
			}
            while (true) {
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
        else {
            // I am a slave
            // know who I am
            ChatServer communicateMaster = null;			
			while (communicateMaster == null)
				communicateMaster = getServerInstance(ip, port, "master");
            role = communicateMaster.getRole();
            server.initiateSlave(ip, port, role);	
            if (role instanceof RoleFrontTier) {
                SL.register_frontend();
				System.out.println("Start recieving request");
                while (true) {
                    Cloud.FrontEndOps.Request r = SL.getNextRequest();
					RequestPack req = new RequestPack(System.currentTimeMillis(), r);
                    communicateMaster.storeRequest(req);
                }
            } else if (role instanceof RoleMidTier) {
            	ChatServer frontLeader = null; 
				while (frontLeader == null)
					frontLeader = getServerInstance(ip, port, "master");
				System.out.println("Start Processing request");
				frontLeader.setFlag();
				try{
            		while (!flagShutDown) {
						long now = System.currentTimeMillis();
            	    	RequestPack req = frontLeader.getRequest();
						if (req == null)
							continue;
						if (!req.r.isPurchase) {
							if (now-req.timeStamp>900)
								SL.drop(req.r);
							else
            	    			SL.processRequest(req.r);
						} else {
							if (now-req.timeStamp>1900)
								SL.drop(req.r);
							else
            	    			SL.processRequest(req.r);
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
	public void run(){
        // Scale for both browse VM and purchase VM
        int curMid = 2;
		try {
			Server.SL.startVM();
			Server.SL.startVM();
			Thread.sleep(5000);
			int countDown = 0;
			boolean lateScaleDown = false;
			long st = System.currentTimeMillis();
    		while (true) {
				if (countDown == 80 && (Server.midTier.size()+Server.roles.size())>2) {
					Role temp = Server.midTier.remove(0);
    		        ChatServer del = Server.getServerInstance(ip, port, temp.nameRegistered);
    		        del.closeRole();
					System.out.println("Role delete"+temp.nameRegistered);
					countDown = 0;
				}
				Thread.sleep(100);
    		    // scale for browse request VM and purchase request VM
    		    ArrayList<Integer> obUp = new ArrayList<Integer>();
    		    ArrayList<Integer> obDown = new ArrayList<Integer>();
    		    obDown.add(Server.requests.size());
    		    obUp.add(Server.requests.size());
				if (!lateScaleDown&&(System.currentTimeMillis()-st)>10000)
					lateScaleDown = true; 
    		    if (Server.checkLength(obUp, true)) {
    		        Role temp = new RoleMidTier("midEnd"+String.valueOf(curMid++));
					Server.roles.add(temp);
    		    	Server.SL.startVM();
					int diff = Server.requests.size()-(Server.roles.size()+Server.midTier.size());
					for (int i=0; i<diff; i++) {
						Cloud.FrontEndOps.Request r = Server.requests.poll().r;
						Server.SL.drop(r);
					}
    		    } else if (lateScaleDown&&Server.checkLength(obDown, false)){
					countDown++;
					continue;
				}
    		}
		} catch(Exception err) {
			err.printStackTrace();
		}
	}
}
