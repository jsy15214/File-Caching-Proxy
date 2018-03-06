import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.ConcurrentHashMap;

public class Server extends UnicastRemoteObject implements RMIserver {
	private static int port;
	private static String rootdir;
	private static int clientID;
	private ConcurrentHashMap<String, Integer> verMap;
	
	Server(int port, String rootdir) throws RemoteException {
		this.port = port;
		this.rootdir = rootdir;
		this.clientID = 0;
		this.verMap = new ConcurrentHashMap<String, Integer>();
	}
	
	public byte[] fetch(String path) throws RemoteException {
		String serverpath = rootdir + "/" + path;
		File f = new File(path);
		int length = (int)f.length();
		byte bytes[] = new byte[length];
		try {
			BufferedInputStream input = new BufferedInputStream(new FileInputStream(serverpath));
			input.read(bytes, 0, length);
			input.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return bytes;
	}
	
	public void write2server(String path, byte[] bytes) throws RemoteException {
		if (verMap.containsKey(path)) {
			verMap.put(path, verMap.get(path) + 1);
		} else {
			verMap.put(path, 0);
		}
		
		String serverpath = rootdir + "/" + path;
		File f = new File(serverpath);
		try {
			RandomAccessFile r = new RandomAccessFile(f, "rw");
			r.write(bytes, 0, bytes.length);
			r.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public int getClientID() throws RemoteException{
		return clientID;
	}
	
	public void setClientID(int clientID) throws RemoteException{
		this.clientID = clientID;
	}
	
	public int getVer(String path) throws RemoteException{
		return verMap.get(path);
	}
	
	public void setVer(String path, int ver) throws RemoteException{
		verMap.put(path, ver);
	}
	
	public static void main(String args[]) {
		int portnumber = Integer.parseInt(args[0]);
		String rootpath = args[1];
		String name = String.format("//127.0.0.1:%d/Server", portnumber);
		try {
			LocateRegistry.createRegistry(portnumber);
			Server server = new Server(portnumber, rootpath);
			Naming.rebind(name, server);
		} catch (RemoteException e) {
			e.printStackTrace();
		} catch (MalformedURLException e) {
			e.printStackTrace();
		}
	}
}
