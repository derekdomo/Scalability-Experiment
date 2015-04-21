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
    public Role getRole() throws RemoteException;
	public void setRole(Role role) throws RemoteException;
    public void closeRole() throws RemoteException;
    public int getLength() throws RemoteException;
    public void storeRequest(RequestPack request) throws RemoteException;
    public RequestPack getRequest() throws RemoteException;
	public Cloud.DatabaseOps getDatabase() throws RemoteException;
	public int getServerLibLength() throws RemoteException;
}

public class Server extends UnicastRemoteObject implements ChatServer{ 
	public Server() throws RemoteException{
		super();
    }
	public static LinkedBlockingDeque<Role> roles = new LinkedBlockingDeque<Role>();
    public static ArrayList<Role> frontTier = new ArrayList<Role>();
    public static ArrayList<Role> midTier= new ArrayList<Role>();
    public static ServerLib SL;
    public static Role role;
    public static LinkedBlockingDeque<RequestPack> requests= new LinkedBlockingDeque<RequestPack>();
	public static ChatServer server; 
	public static boolean flagShutDown = false;
	public static Cloud.DatabaseOps cache;
	public static int frontSize;
	public static int midSize;
	public static long adam;

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
    public int getServerLibLength() {
        return SL.getQueueLength();
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
        return roles.pollFirst();
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

    // Method for Mid Tier to check master's request length
    public int getLength() {
        return requests.size();
    }

    /*
    * RMI Methods for Mid Tier
    * */
    // Methods for Mid Tier to get request
    public RequestPack getRequest() {
    	return requests.poll();
    }
	// Method for Mid Tier to get cache database object
	public Cloud.DatabaseOps getDatabase() {
		return cache;
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
			cache = new CacheDatabase(SL.getDB());
            // Drop the first 5 seconds' requests because of no VM can be started
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
                    // add timeStamp
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
            ChatServer communicateMaster = getServerInstance(ip, port, "master");
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
                // Mid Tier
				try{
                    cache = communicateMaster.getDatabase();
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
			int avgMidLenDown = 0;
			int avgMidLenUp = 0;
            int avgFrontLenUp = 0;
			int countDownMid = 0;
			//int countDownFront = 0;
			int countUp = 0;
			int coolDown = 10000;
			boolean lateScaleDown = false;
			long st_cold_start = System.currentTimeMillis();
			long st_mid_cool_down = 0;
            long st_front_cool_down = 0;
    		while (true) {
				for (Role role:Server.frontTier) {
					if (!frontServer.containsKey(role)) {
						ChatServer temp =
                                Server.getServerInstance(ip, port, role.nameRegistered);
						frontServer.put(role, temp);
					}
				}
				// scale down for Mid Tier
				if (countDownMid == 80) {
					if (Server.midSize>2 && avgMidLenDown<Server.midSize*80
                            && System.currentTimeMillis()-st_mid_cool_down>7000
                            && lateScaleDown) {
						Role temp = Server.midTier.remove(0);
    		        	ChatServer del =
                                Server.getServerInstance(ip, port, temp.nameRegistered);
						Server.midSize -= 1;
    		        	del.closeRole();
                    	System.out.println("Scale down mid tier");
					}
					countDownMid = 0;
					avgMidLenDown = 0;
				}
				// scale up for Front Tier and Mid Tier
				if (countUp == 5) {
                    if (System.currentTimeMillis() - st_front_cool_down > coolDown) {
                        int numToOpen = avgFrontLenUp/5/Server.frontSize;
                        if (numToOpen != 0) {
                            scaleOutFront(numToOpen);
                            st_front_cool_down = System.currentTimeMillis();
                        }
                    }
                    if (System.currentTimeMillis() - st_mid_cool_down > coolDown) {
                        int numToOpen = avgMidLenUp/5/Server.midSize;
						if (numToOpen != 0) {
							scaleOutMid(numToOpen * 2);
							st_mid_cool_down = System.currentTimeMillis();
						}
					}
					countUp = 0;
					avgMidLenUp = 0;
                    avgFrontLenUp = 0;
				}
				Thread.sleep(100);
    		    // Cold scale down for 10 seconds
				if (!lateScaleDown &&
                        (System.currentTimeMillis()-st_cold_start)>10000)
					lateScaleDown = true;
    		    int obMid = Server.requests.size();
                avgFrontLenUp += checkFrontTier(frontServer);
				avgMidLenUp += obMid;
				avgMidLenDown += obMid;
                countUp++;
				countDownMid++;
    		}
		} catch(Exception err) {
			err.printStackTrace();
		}
	}

    public void scaleOutFront(int num) {
        System.out.print("Front Tier Scaled to\t" + num + "\t");
        System.out.println(System.currentTimeMillis() - Server.adam);
        System.out.println("Current Front Tier\t" + Server.frontSize);
        for (int i = 0; i < num && Server.frontSize < 4; i++) {
            Role temp = new RoleFrontTier("frontEnd" + String.valueOf(Server.frontSize++));
            Server.roles.add(temp);
            Server.SL.startVM();
        }
    }

    public void scaleOutMid(int num) {
		System.out.print("Mid Tier Scaled to\t"+num+"\t");
		System.out.println(System.currentTimeMillis() - Server.adam);
        System.out.println("Current Mid Tier\t"+Server.midSize);
		for (int i=0; i<num && Server.midSize < 10; i++) {
    		Role temp = new RoleMidTier("midEnd"+String.valueOf(Server.midSize++));
			Server.roles.add(temp);
    		Server.SL.startVM();
		}
	}

    public int checkFrontTier(HashMap<Role, ChatServer> frontServer) throws Exception {
        int sum = 0;
        for (ChatServer server:frontServer.values())
            sum += server.getServerLibLength();
        return sum+Server.SL.getQueueLength();
    }
}
