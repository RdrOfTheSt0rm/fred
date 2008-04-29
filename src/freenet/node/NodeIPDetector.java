package freenet.node;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Vector;

import freenet.config.InvalidConfigValueException;
import freenet.config.SubConfig;
import freenet.io.comm.FreenetInetAddress;
import freenet.io.comm.Peer;
import freenet.io.comm.UdpSocketHandler;
import freenet.l10n.L10n;
import freenet.node.useralerts.IPUndetectedUserAlert;
import freenet.node.useralerts.InvalidAddressOverrideUserAlert;
import freenet.node.useralerts.SimpleUserAlert;
import freenet.node.useralerts.UserAlert;
import freenet.pluginmanager.DetectedIP;
import freenet.pluginmanager.FredPluginIPDetector;
import freenet.pluginmanager.FredPluginPortForward;
import freenet.support.HTMLNode;
import freenet.support.Logger;
import freenet.support.api.BooleanCallback;
import freenet.support.api.StringCallback;
import freenet.support.transport.ip.HostnameSyntaxException;
import freenet.support.transport.ip.IPAddressDetector;
import freenet.support.transport.ip.IPUtil;

/**
 * Detect the IP address of the node. Doesn't return port numbers, doesn't have access to per-port
 * information (NodeCrypto - UdpSocketHandler etc).
 */
public class NodeIPDetector {

	/** Parent node */
	final Node node;
	/** Ticker */
	/** Explicit forced IP address */
	FreenetInetAddress overrideIPAddress;
	/** Explicit forced IP address in string form because we want to keep it even if it's invalid and therefore unused */
	String overrideIPAddressString;
	/** IP address from last time */
	FreenetInetAddress oldIPAddress;
	/** Detected IP's and their NAT status from plugins */
	DetectedIP[] pluginDetectedIPs;
	/** Last detected IP address */
	FreenetInetAddress[] lastIPAddress;
	/** The minimum reported MTU on all detected interfaces */
	private int minimumMTU = Integer.MAX_VALUE;
	/** IP address detector */
	private final IPAddressDetector ipDetector;
	/** Plugin manager for plugin IP address detectors e.g. STUN */
	final IPDetectorPluginManager ipDetectorManager;
	/** UserAlert shown when ipAddressOverride has a hostname/IP address syntax error */
	private static InvalidAddressOverrideUserAlert invalidAddressOverrideAlert;
	private boolean hasValidAddressOverride;
	/** UserAlert shown when we can't detect an IP address */
	private static IPUndetectedUserAlert primaryIPUndetectedAlert;
	// FIXME redundant? see lastIPAddress
	FreenetInetAddress[] lastIP;
	/** If true, include local addresses on noderefs */
	public boolean includeLocalAddressesInNoderefs;
	/** Set when we have grounds to believe that we may be behind a symmetric NAT. */
	boolean maybeSymmetric;
	private boolean hasDetectedPM;
	private boolean hasDetectedIAD;
	/** Subsidiary detectors: NodeIPPortDetector's which rely on this object */
	private NodeIPPortDetector[] portDetectors;
	private boolean hasValidIP;
	
	SimpleUserAlert maybeSymmetricAlert;
	
	public NodeIPDetector(Node node) {
		this.node = node;
		ipDetectorManager = new IPDetectorPluginManager(node, this);
		ipDetector = new IPAddressDetector(10*1000, this);
		invalidAddressOverrideAlert = new InvalidAddressOverrideUserAlert(node);
		primaryIPUndetectedAlert = new IPUndetectedUserAlert(node);
		portDetectors = new NodeIPPortDetector[0];
	}

	public synchronized void addPortDetector(NodeIPPortDetector detector) {
		NodeIPPortDetector[] newDetectors = new NodeIPPortDetector[portDetectors.length+1];
		System.arraycopy(portDetectors, 0, newDetectors, 0, portDetectors.length);
		newDetectors[portDetectors.length] = detector;
		portDetectors = newDetectors;
	}
	
	/**
	 * What is my IP address? Use all globally available information (everything which isn't
	 * specific to a given port i.e. opennet or darknet) to determine our current IP addresses.
	 * Will include more than one IP in many cases when we are not strictly multi-homed. For 
	 * example, if we have a DNS name set, we will usually return an IP as well.
	 * 
	 * Will warn the user with a UserAlert if we don't have sufficient information.
	 */
	FreenetInetAddress[] detectPrimaryIPAddress() {
		boolean addedValidIP = false;
		Logger.minor(this, "Redetecting IPs...");
		Vector addresses = new Vector();
		if(overrideIPAddress != null) {
			// If the IP is overridden and the override is valid, the override has to be the first element.
			// overrideIPAddress will be null if the override is invalid
			addresses.add(overrideIPAddress);
			if(overrideIPAddress.isRealInternetAddress(false, true, false))
				addedValidIP = true;
		}
		
		if(!node.dontDetect()) {
			addedValidIP |= innerDetect(addresses);
		}
		
	   	if(node.clientCore != null) {
	   		boolean hadValidIP;
	   		synchronized(this) {
	   			hadValidIP = hasValidIP;
	   			hasValidIP = addedValidIP;
	   		}
	   		if(hadValidIP != addedValidIP) {
	   			if (addedValidIP) {
	   				onAddedValidIP();
	   			} else {
	   				onNotAddedValidIP();
	   			}
	   		}
	   	}
	   	synchronized(this) {
	   		hasValidIP = addedValidIP;
	   	}
	   	lastIPAddress = (FreenetInetAddress[]) addresses.toArray(new FreenetInetAddress[addresses.size()]);
	   	return lastIPAddress;
	}
	
	boolean hasValidIP() {
		synchronized(this) {
			return hasValidIP;
		}
	}
	
	private void onAddedValidIP() {
		node.clientCore.alerts.unregister(primaryIPUndetectedAlert);
		node.onAddedValidIP();
	}
	
	private void onNotAddedValidIP() {
		node.clientCore.alerts.register(primaryIPUndetectedAlert);
	}
	
	/**
	 * Core of the IP detection algorithm.
	 * @param addresses
	 * @param addedValidIP
	 * @return
	 */
	private boolean innerDetect(Vector addresses) {
		boolean logMINOR = Logger.shouldLog(Logger.MINOR, this);
		boolean addedValidIP = false;
		InetAddress[] detectedAddrs = ipDetector.getAddress();
		assert(detectedAddrs != null);
		synchronized(this) {
			hasDetectedIAD = true;
		}
		for(int i=0;i<detectedAddrs.length;i++) {
			FreenetInetAddress addr = new FreenetInetAddress(detectedAddrs[i]);
			if(!addresses.contains(addr)) {
				Logger.normal(this, "Detected IP address: "+addr);
				addresses.add(addr);
				if(addr.isRealInternetAddress(false, false, false))
					addedValidIP = true;
			}
		}
		
		if((pluginDetectedIPs != null) && (pluginDetectedIPs.length > 0)) {
			for(int i=0;i<pluginDetectedIPs.length;i++) {
				InetAddress addr = pluginDetectedIPs[i].publicAddress;
				if(addr == null) continue;
				FreenetInetAddress a = new FreenetInetAddress(addr);
				if(!addresses.contains(a)) {
					Logger.normal(this, "Plugin detected IP address: "+a);
					addresses.add(a);
					if(a.isRealInternetAddress(false, false, false))
						addedValidIP = true;
				}
			}
		}
		
		if((!addedValidIP) && (oldIPAddress != null) && !oldIPAddress.equals(overrideIPAddress)) {
			addresses.add(oldIPAddress);
			if(oldIPAddress.isRealInternetAddress(false, true, false))
				addedValidIP = true;
		}
		
		// Try to pick it up from our connections
		if(node.peers != null) {
			PeerNode[] peerList = node.peers.connectedPeers;
			HashMap countsByPeer = new HashMap();
			// FIXME use a standard mutable int object, we have one somewhere
			for(int i=0;i<peerList.length;i++) {
				Peer p = peerList[i].getRemoteDetectedPeer();
				if(p == null || p.isNull()) continue;
				FreenetInetAddress addr = p.getFreenetAddress();
				if(addr == null) continue;
				if(!IPUtil.isValidAddress(addr.getAddress(false), false)) continue;
				if(logMINOR)
					Logger.minor(this, "Peer "+peerList[i].getPeer()+" thinks we are "+addr);
				if(countsByPeer.containsKey(addr)) {
					Integer count = (Integer) countsByPeer.get(addr);
					Integer newCount = new Integer(count.intValue()+1);
					countsByPeer.put(addr, newCount);
				} else {
					countsByPeer.put(addr, new Integer(1));
				}
			}
			if(countsByPeer.size() == 1) {
				Iterator it = countsByPeer.keySet().iterator();
				FreenetInetAddress addr = (FreenetInetAddress) (it.next());
				Logger.minor(this, "Everyone agrees we are "+addr);
				if(!addresses.contains(addr)) {
					if(addr.isRealInternetAddress(false, false, false))
						addedValidIP = true;
					addresses.add(addr);
				}
			} else if(countsByPeer.size() > 1) {
				Iterator it = countsByPeer.keySet().iterator();
				// Take two most popular addresses.
				FreenetInetAddress best = null;
				FreenetInetAddress secondBest = null;
				int bestPopularity = 0;
				int secondBestPopularity = 0;
				while(it.hasNext()) {
					FreenetInetAddress cur = (FreenetInetAddress) (it.next());
					int curPop = ((Integer) (countsByPeer.get(cur))).intValue();
					Logger.normal(this, "Detected peer: "+cur+" popularity "+curPop);
					if(curPop >= bestPopularity) {
						secondBestPopularity = bestPopularity;
						bestPopularity = curPop;
						secondBest = best;
						best = cur;
					}
				}
				if(best != null) {
					if((bestPopularity > 1) || (detectedAddrs.length == 0)) {
 						if(!addresses.contains(best)) {
							Logger.normal(this, "Adding best peer "+best+" ("+bestPopularity+ ')');
							addresses.add(best);
							if(best.isRealInternetAddress(false, false, false))
								addedValidIP = true;
						}
						if((secondBest != null) && (secondBestPopularity > 1)) {
							if(!addresses.contains(secondBest)) {
								Logger.normal(this, "Adding second best peer "+secondBest+" ("+secondBest+ ')');
								addresses.add(secondBest);
								if(secondBest.isRealInternetAddress(false, false, false))
									addedValidIP = true;
							}
						}
					}
				}
			}
		}
	   	return addedValidIP;
	}
	
	private String l10n(String key) {
		return L10n.getString("NodeIPDetector."+key);
	}

	private String l10n(String key, String pattern, String value) {
		return L10n.getString("NodeIPDetector."+key, pattern, value);
	}

	FreenetInetAddress[] getPrimaryIPAddress() {
		if(lastIPAddress == null) return detectPrimaryIPAddress();
		return lastIPAddress;
	}
	
	public boolean hasDirectlyDetectedIP() {
		InetAddress[] addrs = ipDetector.getAddress();
		if(addrs == null || addrs.length == 0) return false;
		for(int i=0;i<addrs.length;i++) {
			if(IPUtil.isValidAddress(addrs[i], false)) {
				if(Logger.shouldLog(Logger.MINOR, this))
					Logger.minor(this, "Has a directly detected IP: "+addrs[i]);
				return true;
			}
		}
		return false;
	}

	/**
	 * Process a list of DetectedIP's from the IP detector plugin manager.
	 * DetectedIP's can tell us what kind of NAT we are behind as well as our public
	 * IP address.
	 */
	public void processDetectedIPs(DetectedIP[] list) {
		pluginDetectedIPs = list;
		for(int i=0; i<pluginDetectedIPs.length; i++){
			int mtu = pluginDetectedIPs[i].mtu;
			if(minimumMTU > mtu && mtu > 0){
				minimumMTU = mtu;
				Logger.normal(this, "Reducing the MTU to "+minimumMTU);
	    		node.onTooLowMTU(minimumMTU, UdpSocketHandler.MIN_MTU);
			}
		}
		redetectAddress();
	}

	public void redetectAddress() {
		FreenetInetAddress[] newIP = detectPrimaryIPAddress();
		NodeIPPortDetector[] detectors;
		synchronized(this) {
			if(Arrays.equals(newIP, lastIP)) return;
			lastIP = newIP;
			detectors = portDetectors;
		}
		for(int i=0;i<detectors.length;i++)
			detectors[i].update();
		node.writeNodeFile();
	}

	public void setOldIPAddress(FreenetInetAddress freenetAddress) {
		this.oldIPAddress = freenetAddress;
	}

	public boolean includeLocalAddressesInNoderefs() {
		return includeLocalAddressesInNoderefs;
	}

	public int registerConfigs(SubConfig nodeConfig, int sortOrder) {
		// IP address override
		nodeConfig.register("ipAddressOverride", "", sortOrder++, true, false, "NodeIPDectector.ipOverride", 
				"NodeIPDectector.ipOverrideLong", 
				new StringCallback() {

			public String get() {
				if(overrideIPAddressString == null) return "";
				else return overrideIPAddressString;
			}
			
			public void set(String val) throws InvalidConfigValueException {
				boolean hadValidAddressOverride = hasValidAddressOverride();
				// FIXME do we need to tell anyone?
				if(val.length() == 0) {
					// Set to null
					overrideIPAddressString = val;
					overrideIPAddress = null;
					lastIPAddress = null;
					redetectAddress();
					return;
				}
				FreenetInetAddress addr;
				try {
					addr = new FreenetInetAddress(val, false, true);
				} catch (HostnameSyntaxException e) {
					throw new InvalidConfigValueException(l10n("unknownHostErrorInIPOverride", "error", "hostname or IP address syntax error"));
				} catch (UnknownHostException e) {
					throw new InvalidConfigValueException(l10n("unknownHostErrorInIPOverride", "error", e.getMessage()));
				}
				// Compare as IPs.
				if(addr.equals(overrideIPAddress)) return;
				overrideIPAddressString = val;
				overrideIPAddress = addr;
				lastIPAddress = null;
				synchronized(this) {
					hasValidAddressOverride = true;
				}
				if(!hadValidAddressOverride) {
					onGetValidAddressOverride();
				}
				redetectAddress();
			}
			
		});
		
		hasValidAddressOverride = true;
		overrideIPAddressString = nodeConfig.getString("ipAddressOverride");
		if(overrideIPAddressString.length() == 0)
			overrideIPAddress = null;
		else {
			try {
				overrideIPAddress = new FreenetInetAddress(overrideIPAddressString, false, true);
			} catch (HostnameSyntaxException e) {
				synchronized(this) {
					hasValidAddressOverride = false;
				}
				String msg = "Invalid IP override syntax: "+overrideIPAddressString+" in config: "+e.getMessage();
				Logger.error(this, msg);
				System.err.println(msg+" but starting up anyway, ignoring the configured IP override");
				overrideIPAddress = null;
			} catch (UnknownHostException e) {
				// **FIXME** This never happens for this reason with current FreenetInetAddress(String, boolean, boolean) code; perhaps it needs review?
				String msg = "Unknown host: "+overrideIPAddressString+" in config: "+e.getMessage();
				Logger.error(this, msg);
				System.err.println(msg+" but starting up anyway with no IP override");
				overrideIPAddress = null;
			}
		}
		
		// Temporary IP address hint
		
		nodeConfig.register("tempIPAddressHint", "", sortOrder++, true, false, "NodeIPDectector.tempAddressHint", "NodeIPDectector.tempAddressHintLong", new StringCallback() {

			public String get() {
				return "";
			}
			
			public void set(String val) throws InvalidConfigValueException {
				if(val.length() == 0) {
					return;
				}
				if(overrideIPAddress != null) return;
				try {
					oldIPAddress = new FreenetInetAddress(val, false);
				} catch (UnknownHostException e) {
					throw new InvalidConfigValueException("Unknown host: "+e.getMessage());
				}
				redetectAddress();
			}
			
		});
		
		String ipHintString = nodeConfig.getString("tempIPAddressHint");
		if(ipHintString.length() > 0) {
			try {
				oldIPAddress = new FreenetInetAddress(ipHintString, false);
			} catch (UnknownHostException e) {
				String msg = "Unknown host: "+ipHintString+" in config: "+e.getMessage();
				Logger.error(this, msg);
				System.err.println(msg+"");
				oldIPAddress = null;
			}
		}
		
		// Include local IPs in noderef file
		
		nodeConfig.register("includeLocalAddressesInNoderefs", false, sortOrder++, true, false, "NodeIPDectector.inclLocalAddress", "NodeIPDectector.inclLocalAddressLong", new BooleanCallback() {

			public boolean get() {
				return includeLocalAddressesInNoderefs;
			}

			public void set(boolean val) throws InvalidConfigValueException {
				includeLocalAddressesInNoderefs = val;
				lastIPAddress = null;
				ipDetector.clearCached();
			}
			
		});
		
		includeLocalAddressesInNoderefs = nodeConfig.getBoolean("includeLocalAddressesInNoderefs");
		
		return sortOrder;
	}

	/** Start all IP detection related processes */
	public void start() {
		boolean haveValidAddressOverride = hasValidAddressOverride();
		if(!haveValidAddressOverride) {
			onNotGetValidAddressOverride();
		}
		ipDetectorManager.start();
		node.executor.execute(ipDetector, "IP address re-detector");
		redetectAddress();
		// 60 second delay for inserting ARK to avoid reinserting more than necessary if we don't detect IP on startup.
		// Not a FastRunnable as it can take a while to start the insert
		node.getTicker().queueTimedJob(new Runnable() {
			public void run() {
				NodeIPPortDetector[] detectors;
				synchronized(this) {
					detectors = portDetectors;
				}
				for(int i=0;i<detectors.length;i++)
					detectors[i].startARK();
			}
		}, 60*1000);
	}

	public void onConnectedPeer() {
		ipDetectorManager.maybeRun();
	}

	public void registerIPDetectorPlugin(FredPluginIPDetector detector) {
		ipDetectorManager.registerDetectorPlugin(detector);
	}

	public void unregisterIPDetectorPlugin(FredPluginIPDetector detector) {
		ipDetectorManager.unregisterDetectorPlugin(detector);
	}
	
	public synchronized boolean isDetecting() {
		return !(hasDetectedPM && hasDetectedIAD);
	}

	void hasDetectedPM() {
		synchronized(this) {
			hasDetectedPM = true;
		}
	}

	public int getMinimumDetectedMTU() {
		return minimumMTU > 0 ? minimumMTU : 1500;
	}

	public void setMaybeSymmetric() {
		if(ipDetectorManager != null && ipDetectorManager.isEmpty()) {
			if(maybeSymmetricAlert == null) {
				maybeSymmetricAlert = new SimpleUserAlert(true, l10n("maybeSymmetricTitle"), 
						l10n("maybeSymmetric"), l10n("maybeSymmetricShort"), UserAlert.ERROR);
			}
			if(node.clientCore != null && node.clientCore.alerts != null)
				node.clientCore.alerts.register(maybeSymmetricAlert);
		} else {
			if(maybeSymmetricAlert != null)
				node.clientCore.alerts.unregister(maybeSymmetricAlert);
		}
	}

	public void registerPortForwardPlugin(FredPluginPortForward forward) {
		ipDetectorManager.registerPortForwardPlugin(forward);
	}

	public void unregisterPortForwardPlugin(FredPluginPortForward forward) {
		ipDetectorManager.unregisterPortForwardPlugin(forward);
	}
	
	boolean hasValidAddressOverride() {
		synchronized(this) {
			return hasValidAddressOverride;
		}
	}
	
	private void onGetValidAddressOverride() {
		node.clientCore.alerts.unregister(invalidAddressOverrideAlert);
	}
	
	private void onNotGetValidAddressOverride() {
		node.clientCore.alerts.register(invalidAddressOverrideAlert);
	}

	public void addConnectionTypeBox(HTMLNode contentNode) {
		ipDetectorManager.addConnectionTypeBox(contentNode);
	}
}
