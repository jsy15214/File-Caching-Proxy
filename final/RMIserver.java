import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * This is the protocol between Server and Proxy.
 */
public interface RMIserver extends Remote {
	/**
	* Generate a private path for each new client 
    *  ==> ensure concurrent client to read/write at separate paths.
	*/
	int getClientID() throws RemoteException;
	void setClientID(int clientID) throws RemoteException;
	/**
	 * Update version number on the server after 
	 * each modification to ensure cache freshness. 
	 * */
	int getVer(String path) throws RemoteException;
	void setVer(String path, int ver) throws RemoteException;
	/**
	Enable file transfer between proxy and server
	*/
	byte[] fetch(String path) throws RemoteException;
	void write2server(String path, byte[] bytes) throws RemoteException;
}