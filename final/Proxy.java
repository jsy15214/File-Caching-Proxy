/* Sample skeleton for proxy */

import java.io.*;
import java.rmi.Naming;
import java.rmi.RemoteException;
import java.util.Map.Entry;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 
 * LRU cache.
 * Enables accessing file from cache and pushing new file to cache.
 * Eviction based on LRU rule when cache size is not enough.
 *
 * @param <String> path
 * @param <CacheEntry> cache block
 */
@SuppressWarnings("hiding")
class LRU<String, CacheEntry> {
	//double linked list
	class Node<String, CacheEntry> {
		String path;
		CacheEntry c;
		Node<String, CacheEntry> prev;
		Node<String, CacheEntry> next;
		
		Node(String path, CacheEntry c) {
			this.path = path;
			this.c = c;
		}
	}
	
	private final int capacity;
	private Node<String, CacheEntry> head;
	private Node<String, CacheEntry> tail;
	private ConcurrentHashMap<String, Node<String, CacheEntry>> cache;
	
	LRU (int capacity) {
		this.capacity = capacity;
		this.cache = new ConcurrentHashMap<String, Node<String, CacheEntry>>();
	}
	
	//remove a node from cache
	private synchronized void remove(Node<String, CacheEntry> n) {
		cache.remove(n.path);
		if (n == head && n == tail) {
			head = null;
			tail = null;
		} else if (n == head){
			head = head.next;
			head.prev = null;
		} else if (n == tail)
		if (n.next == null) {
			tail = tail.prev;
			tail.next = null;
		} else {
			n.prev.next = n.next;
			n.next.prev = n.prev;
		}
		n.prev = null;
		n.next = null;	
	}
	
	//move the node to head
	private synchronized void insert(Node<String, CacheEntry> n) {
		cache.put(n.path, n);
		if (head == null) {
			head = n;
			tail = n;
		} else {
			n.next = head;
			head.prev = n;
			head = n;
		}
	}
	
	/**
	 * Move path--cache entry to front of the DLL
	 * @param path
	 * @return cache entry visited
	 */
	synchronized CacheEntry get(String path) {
		Node<String, CacheEntry> n = cache.get(path);
		if (n == null) {
			return null;
		} else {
			remove(n);
			insert(n);
			return n.c;
		}
	}
	
	/**
	 * Put c into LRU cache. Evict if necessary
	 * @param path
	 * @param c
	 */
	synchronized void set(String path, CacheEntry c) {
		//move the path to MRU(head)
		Node<String, CacheEntry> n = null;
		if (cache.containsKey(path)) {
			//hit, update its value and remove the original node
			n = cache.get(path);
			n.c = c;
			remove(n);
		} else if (cache.size() < capacity) {
			//miss, but there's still space for new cache entry
			n = new Node<String, CacheEntry>(path, c);
		} else {
			//miss and no extra space: evict LRU(tail)
			n = tail;
			remove(n);
			n = new Node<String, CacheEntry>(path, c);
		}
		insert(n);
	}	
}

/**
 * This class holds the file information. 
 */
class FileHeader {
	RandomAccessFile r;
	File f;
	String path;
	boolean isReadOnly;
	String privatepath;
	
	public FileHeader(File f, String path, boolean isReadOnly, String privatepath) {
		this.f = f;
		this.path = path;
		this.isReadOnly = isReadOnly;
		this.privatepath = privatepath;
	}
	
	public FileHeader(RandomAccessFile r, File f, String path, boolean isReadOnly, String privatepath) {
		this.r = r;
		this.f = f;
		this.path = path;
		this.isReadOnly = isReadOnly;
		this.privatepath = privatepath;
	}
}

/**
 * This class records the current cachepath, proxy version number, 
 * whether its modified or being opened.
 */
class CacheEntry {
	String cachepath;
	int ver;
	boolean m;
	
	public CacheEntry(String cachepath, int ver, boolean m) {
		this.cachepath = cachepath;
		this.ver = ver;
		this.m = m;
	}
}

class Proxy {
	private static String serverip;
	private static int port;
	private static String cachedir;
	private static int cachesize;
	private static LRU<String, CacheEntry> lru;
	private static RMIserver server;
	
	private static class FileHandler implements FileHandling {
		private ConcurrentHashMap<Integer, FileHeader> FileMap;
		private int open_count; //to determine current fd when open
		private static int clientID;
		
		public FileHandler() {
			this.FileMap = new ConcurrentHashMap<Integer, FileHeader>();
			open_count = 0;
		}
		
		private String getCachePath(String path) {
			return cachedir + "/" + path;
		}
		
		private String getPrivatePath(String path) {
			return cachedir + "/" + "copy" + String.valueOf(clientID) + "_" + path;
		}
		
		private synchronized int downloadFile2Cache(String path, String cachepath) {
			byte bytes[] = null;
			try {
				bytes = server.fetch(path);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
			try (FileOutputStream fos = new FileOutputStream(cachepath)) {
				   fos.write(bytes);
				   fos.close();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			return bytes.length;
		}
		
		/**
		 * Copy file content from source to destination
		 * @param source
		 * @param dest
		 */
		private synchronized void makeCopy(String source, String dest) {
			InputStream input = null;
			OutputStream output = null;
			try {
				try {
					input = new FileInputStream(source);
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
				try {
					output = new FileOutputStream(dest);
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
				byte[] buf = new byte[1024];
				int bytesRead;
				try {
					while ((bytesRead = input.read(buf)) > 0) {
						output.write(buf, 0, bytesRead);
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			} finally {
				try {
					input.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
				try {
					output.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

		}
		
		/**
		 * Simplify path to valid pathname
		 * @param path
		 * @return valid pathname
		 */
		private String get_orig(String path) {
			String[] strs = path.split("/");
			//Use stack to reconstruct the path
			Stack<String> stack = new Stack<String>();
			
			for (String str: strs) {
				if (str.length() == 0 || str.equals(".")) {
					continue;
				} else if (str.equals("..")) {
					if (!stack.isEmpty()) {
						stack.pop();
					}
				} else {
					stack.push(str);
				}
			}
			if (stack.isEmpty()) {
				return "/";
			} 
			
			StringBuilder sb = new StringBuilder();
			while (!stack.isEmpty()) {
				String s = stack.pop();
				sb.insert(0, "/" + s);
			}
			
			String res = sb.toString();
			return res.substring(1, res.length());
		}

		public synchronized int open( String path, OpenOption o ) {
			
			//Check and Simlify path
			try {
				String pathdir = new File(cachedir + "/" +path).getCanonicalPath();
				String pathcur = new File(cachedir).getCanonicalPath();
				if (!pathdir.contains(pathcur)) {
					return Errors.ENOENT;
				}
			} catch(Exception e) {
				e.printStackTrace();
			}
			path = get_orig(path);
			
			
			int index = open_count;
			open_count ++;
			File f;
			
			String cachepath = getCachePath(path);
			String privatepath = getPrivatePath(path);
			int proxyver = 0;
			
			//Hit
			if (lru.get(path) != null) {
				//get cache path
				cachepath = lru.get(path).cachepath;
				proxyver = lru.get(path).ver;
				int serverver = 0;
				try {
					serverver = server.getVer(path);
				} catch (RemoteException e) {
					e.printStackTrace();
				}
				lru.get(path).m = false;
				
				//update cache block to newest version
				if (proxyver < serverver) {
					downloadFile2Cache(path, cachepath);
					lru.get(path).ver = serverver;
;				}
				f = new File(cachepath);
			} else {
				//Miss: fetch file from server
				CacheEntry c = new CacheEntry(cachepath, proxyver, false);
				lru.set(path, c);
				try {
					server.setVer(path, 0);
					downloadFile2Cache(path, cachepath);
				} catch (RemoteException e1) {
					e1.printStackTrace();
				}
				
				f = new File(cachepath);
				
				if (!f.exists()) {
					try {
						f.createNewFile();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			
			System.err.println("open: " + "option = " + o + "fd = " + index+ "  " + path);
			
			//Start opening file
			if (o.equals(OpenOption.READ)) {
				if (!f.exists()) {
					return Errors.ENOENT;
				}
				
				FileHeader fh = new FileHeader(f, path, true,null);
				FileMap.put(index, fh);
				RandomAccessFile r = null;
				if (!f.isDirectory()) {
				
				try {
						r = new RandomAccessFile(f, "r");
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					}
				
				if (r != null) {
					FileHeader newfh = new FileHeader(r, f, path, true, null);
					FileMap.put(index, newfh);
				}
				}
				
			} else if (o.equals(OpenOption.WRITE)) {
				f = new File(privatepath);
				makeCopy(cachepath, privatepath);
				
				if (!f.exists()) {
					return Errors.ENOENT;
				} else if (f.isDirectory()) {
					return Errors.EISDIR;
				}
				
				RandomAccessFile r = null;
				try {
					r = new RandomAccessFile(f, "rw");
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
				
				if (r == null) {
					open_count --;
					return Errors.EBADF;
				}
				FileHeader fh = new FileHeader(r, f, path, false, privatepath);
				FileMap.put(index, fh);
				
			} else if (o.equals(OpenOption.CREATE)) {
				f = new File(privatepath);
				makeCopy(cachepath, privatepath);
				
				if (f.isDirectory()) {
					return Errors.EISDIR;
				}
				
				if (!f.exists()) {
					try {
						f.createNewFile();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				
				RandomAccessFile r = null;
				try {
					r = new RandomAccessFile(f, "rw");
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
				
				if (r == null) {
					open_count --;
					return Errors.EBADF;
				}
				FileHeader fh = new FileHeader(r, f, path, false, privatepath);
				FileMap.put(index, fh);
				
			} else if (o.equals(OpenOption.CREATE_NEW)) {
				f = new File(privatepath);
				makeCopy(cachepath, privatepath);
				if (f.exists()) {
					return Errors.EEXIST;
				} else if (f.isDirectory()) {
					return Errors.EISDIR;
				}
				
				try {
					f.createNewFile();
				} catch (IOException e) {
					e.printStackTrace();
				}
				
				RandomAccessFile r = null;
				try {
					r = new RandomAccessFile(f, "rw");
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
				if (r == null) {
					open_count --;
					return Errors.EBADF;
				}
				FileHeader fh = new FileHeader(r, f, path, false, privatepath);
				FileMap.put(index, fh);
				
			} else {
				return Errors.EINVAL;
			}
			
			return index;
		}

		public synchronized int close( int fd ) {
			System.err.println("close: " + fd);
			FileHeader fh = FileMap.get(fd);
			RandomAccessFile r = fh.r;
			if (r == null) {
				return Errors.EBADF;
			}
			
			String path = fh.path;
			String cachepath = lru.get(path).cachepath;
			//update private modification to cache
			if (!fh.isReadOnly) {
				makeCopy(fh.privatepath, cachepath);
				File pfile = new File(fh.privatepath);
				pfile.delete();
			}
			
			//If cache block modified, push changes to server
			if (lru.get(path).m) {
				File newF = new File(cachepath);
				int length = (int)newF.length();
				byte bytes[] = new byte[length];
				//read from cache
				try (BufferedInputStream fos = new BufferedInputStream(new FileInputStream(cachepath))) {
					   fos.read(bytes, 0, length);
					   fos.close();
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
				//write to server
				try {
					server.write2server(path, bytes);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			
			try {
				r.close();
			} catch (IOException e) {
				e.printStackTrace();
				return Errors.EBADF;
			}
			
			FileMap.remove(fd);
			return 0;
		}

		public long write( int fd, byte[] buf ) {
			System.err.println("write: " + fd);
			FileHeader fh = FileMap.get(fd);
			RandomAccessFile r = fh.r;
			File f = fh.f;
			if (r == null) {
				return Errors.EBADF;
			} else if (f.isDirectory()) {
				return Errors.EISDIR;
			} else if (fh.isReadOnly) {
				return Errors.EBADF;
			} else if (!f.canWrite()) {
				return Errors.EINVAL;
			}
			
			try {
				r.write(buf);
			} catch (IOException e) {
				e.printStackTrace();
				return Errors.EBADF;
			}
			
			lru.get(fh.path).m = true;
			return buf.length;
		}

		public long read( int fd, byte[] buf ) {
			System.err.println("read: " + fd);
			FileHeader fh = FileMap.get(fd);
			RandomAccessFile r = fh.r;
			File f = fh.f;
			  if (f.isDirectory()) {
				return Errors.EISDIR;
			} else if (!f.canRead()) {
				return Errors.EINVAL;
			} else if (r == null) {
				return Errors.EBADF;
			}
			
			int res = 0;
			try {
				res = r.read(buf);
			} catch (IOException e) {
				e.printStackTrace();
				return Errors.EBADF;
			}
			
			if (res < 0) res = 0;
			return res;
		}

		public long lseek( int fd, long pos, LseekOption o ) {
			System.err.println("lseek: " + fd);
			FileHeader fh = FileMap.get(fd);
			RandomAccessFile r = fh.r;
			if (r == null) {
				return Errors.EBADF;
			}
			
			long res = pos;
			if (o.equals(LseekOption.FROM_CURRENT)) {
				try {
					res = r.getFilePointer() + pos;
					r.seek(res);
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else if (o.equals(LseekOption.FROM_END)) {
				try {
					res = r.length() - pos;
					r.seek(res);
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else if (o.equals(LseekOption.FROM_START)) {
				try {
					r.seek(res);
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else {
				return Errors.EINVAL;
			}
			
			return res;
		}

		public int unlink( String path ) {
			System.err.println("unlink: " + path);
			File f = new File(path);
			if (!f.isFile()) {
				return Errors.ENOENT;
			}
			
			for (Entry<Integer, FileHeader> filemap_entry : FileMap.entrySet()) {
				if (filemap_entry.getValue().f.equals(f)) {
					FileMap.remove(filemap_entry.getKey());
				}
			}
			
			f.delete();
			return f.exists() ? -1 : 0;
		}

		public void clientdone() {
			if (FileMap.isEmpty()) return;
			
			for (Entry<Integer, FileHeader> rfamap_entry: FileMap.entrySet()) {
				RandomAccessFile r = rfamap_entry.getValue().r;
				if (r != null) {
					try {
						r.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				FileMap.remove(rfamap_entry.getKey());
			}
		}
	}
	
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			FileHandler fh = new FileHandler();
			try {
				FileHandler.clientID = server.getClientID();
				server.setClientID(FileHandler.clientID + 1);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
			return fh;
		}
	}

	public static void main(String[] args) throws IOException {
		serverip = args[0];
		port = Integer.parseInt(args[1]);
		cachedir = args[2];
		cachesize = Integer.parseInt(args[3]);
		lru = new LRU<String, CacheEntry>(cachesize);
		
		//connect to server
		serverip = "//127.0.0.1";
		String fullname = String.format(serverip + ":%d/Server", port);
		try {
			server = (RMIserver)Naming.lookup(fullname);
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}

