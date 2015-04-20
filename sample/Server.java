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
	public Cloud.DatabaseOps getDatabase() throws RemoteException;
    public boolean scaleUpFront() throws RemoteException;
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
	public static boolean startNotification = false; 
	public static ChatServer server; 
	public static boolean flagShutDown = false;
	public static Cloud.DatabaseOps cache;
	public static int frontSize;
	public static int midSize;

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

    public boolean scaleUpFront() {
        if (frontSize > 3)
            return false;
        Role temp = new RoleFrontTier("frontEnd"+String.valueOf(frontSize++));
        roles.add(temp);
        SL.startVM();
        return true;
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
			while (System.currentTimeMillis()-st<5000) {
                if (SL.getQueueLength()>0)
				    SL.dropHead();
			}
            while (true) {
				try {
                    if (SL.getQueueLength()==0) {
                        // parameter
                        Thread.sleep(30);
						continue;
					}
                	Cloud.FrontEndOps.Request r = SL.getNextRequest();
					RequestPack req = new RequestPack(System.currentTimeMillis(), r);
                	requests.add(req);
                    if (SL.getQueueLength()>1) {
                        if (frontSize > 3)
                            continue;
                        Role temp = new RoleFrontTier("frontEnd"+String.valueOf(frontSize++));
                        roles.add(temp);
                        SL.startVM();
                    }

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
			while (communicateMaster == null)
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
                        if (SL.getQueueLength()==0) {
                            //parameter
                            Thread.sleep(30);
							continue;
						}
                		Cloud.FrontEndOps.Request r = SL.getNextRequest();
						RequestPack req = new RequestPack(System.currentTimeMillis(), r);
                		communicateMaster.storeRequest(req);
                        if (SL.getQueueLength()>1) {
							
                        }
					}
				} catch (Exception err){
					err.printStackTrace();
				}
			} else {
				try{
					System.out.println("Mid Tier");
            		while (!flagShutDown) {
                        if (communicateMaster.getLength() == 0) {
                            //parameter
                            Thread.sleep(30);
							continue;
						}
						long now = System.currentTimeMillis();
            	    	RequestPack req = communicateMaster.getRequest();
						if (req == null)
							continue;
						if (!req.r.isPurchase) {
							//parameter
							if (now-req.timeStamp>800) {
								System.out.print("Browse\t");
								System.out.println(now-req.timeStamp);
								SL.drop(req.r);
							}
							else
            	    			SL.processRequest(req.r, cache);
						} else {
							//parameter
							if (now-req.timeStamp>1300) {
								System.out.print("Purchase\t");
								System.out.println(now-req.timeStamp);
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
	public void run(){
        // Scale for both browse VM and purchase VM
		try {
			Server.SL.startVM();
			Server.SL.startVM();
			Server.SL.startVM();
			HashMap<Role, ChatServer> frontServer = new HashMap<Role, ChatServer>();
			Thread.sleep(5000);
			int countDownMid = 0;
			int countDownFront = 0;
			boolean lateScaleDown = false;
			long st = System.currentTimeMillis();
    		while (true) {
				// scale for Front Tier and Mid Tier
				if (countDownMid == 100 && Server.midSize>2) {
					Role temp = Server.midTier.remove(0);
    		        ChatServer del = Server.getServerInstance(ip, port, temp.nameRegistered);
					Server.midSize -= 1;
    		        del.closeRole();
					countDownMid = 0;
                    System.out.println("Scale down mid end");
				}
				Thread.sleep(100);
				if (!lateScaleDown&&(System.currentTimeMillis()-st)>10000)
					lateScaleDown = true; 
    		    int obMid = Server.requests.size();
    		    if (obMid > Server.midSize && Server.midSize<10) {
                    int diff = Server.requests.size() - Server.midSize;
					int num = diff/Server.midSize;
                    for (int i=0; i<num; i++) {
    		            Role temp = new RoleMidTier("midEnd"+String.valueOf(Server.midSize++));
					    Server.roles.add(temp);
    		    	    Server.SL.startVM();
                    }
                    countDownMid = 0;
    		    } else if (lateScaleDown && obMid < Server.midSize){
					countDownMid++;
				}
    		}
		} catch(Exception err) {
			err.printStackTrace();
		}
	}
}
