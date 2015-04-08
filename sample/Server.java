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
    public void storeRequest(Cloud.FrontEndOps.Request request) throws RemoteException;
    public Cloud.FrontEndOps.Request getRequest() throws RemoteException;
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
    public static LinkedList<Cloud.FrontEndOps.Request> requests= new LinkedList<Cloud.FrontEndOps.Request>();
    public static int threshHoldAvg = 2;
	public static boolean startNotification = false; 

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
            UnicastRemoteObject.unexportObject(this, true);
        } catch(NoSuchObjectException err) {
            err.printStackTrace();
        }
    }

    public int getLength() {
    	return requests.size();
    }

    /*
    * RMI Methods for Front Tier
    * */
    // Methods for the front tier VM to call the leader 
    public void storeRequest(Cloud.FrontEndOps.Request request) {
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
    public Cloud.FrontEndOps.Request getRequest() {
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
       	 	    if (it<midTier.size())
       	 	    	return false;
       	 	}
			return true;
		} else {
       		for (Integer it:ob) {
       	 	    if (it>midTier.size())
       	 	    	return false;
       	 	}
			return true;
		}
    }

	public static void main ( String args[] ) throws Exception {
		if (args.length != 2) throw new Exception("Need 2 args: <cloud_ip> <cloud_port>");
        String ip = args[0];
        int port = Integer.parseInt(args[1]);

        ChatServer server = new Server();
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
			for (int i=0; i<countForMid+countForFront; i++) {
				//Thread tempThread =  new Thread(SL.startVM());
                //tempThread.run();
                SL.startVM();
			}
            SL.register_frontend();
			Thread sch = new Thread(new Schedule(server, ip, port));
			sch.start();
			long start = System.currentTimeMillis();
			while (System.currentTimeMillis()-start<3000) {
                Cloud.FrontEndOps.Request r = SL.getNextRequest();
				SL.drop(r);	
			}
			System.out.println("Start recieving request");
            while (true) {
                Cloud.FrontEndOps.Request r = SL.getNextRequest();
                requests.add(r);
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
                    communicateMaster.storeRequest(r);
                }
            } else if (role instanceof RoleMidTier) {
            	ChatServer frontLeader = null; 
				while (frontLeader == null)
					frontLeader = getServerInstance(ip, port, "master");
				System.out.println("Start Processing request");
				frontLeader.setFlag();
            	while (true) {
            	    Cloud.FrontEndOps.Request r = frontLeader.getRequest();
            	    SL.processRequest(r);
				}
            }
        }
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
	public ChatServer server;
	public String ip;
	public int port;
	public Schedule(ChatServer server, String ip, int port) {
		this.server = server;
		this.ip = ip;
		this.port = port;
	}	
	public void run(){
        // Scale for both browse VM and purchase VM
        int curMid = 2;
		try {
			Thread.sleep(5000);
			int countUp = 0;
			int countDown = 0;
    		while (true) {
				if (countUp == 4 && (Server.midTier.size()+Server.roles.size())<14) {
    		        Role temp = new RoleMidTier("midEnd"+String.valueOf(curMid++));
					Server.roles.add(temp);
    		    	Server.SL.startVM();
					countUp = 0;
				}
				if (countDown == 10 && (Server.midTier.size()>2)) {
					Role temp = Server.midTier.remove(0);
    		        ChatServer del = Server.getServerInstance(ip, port, temp.nameRegistered);
    		        del.closeRole();
					countDown = 0;
				}
				Thread.sleep(250);
    		    // scale for browse request VM and purchase request VM
    		    ArrayList<Integer> obUp = new ArrayList<Integer>();
    		    ArrayList<Integer> obDown = new ArrayList<Integer>();
    		    int acc = 0;
    		    for (int i=0; i<5; i++) {
    		        obDown.add(Server.requests.size());
    		        obUp.add(Server.requests.size());
    		    }
    		    if (Server.checkLength(obUp, true)) {
					countUp++;
					countDown = 0;
					continue;
    		    } else if (Server.checkLength(obDown, false)){
					countDown++;
					countUp = 0;
					continue;
				}
				countDown=0;
				countUp=0;
    		}
		} catch(Exception err) {
			err.printStackTrace();
		}
	}
}
