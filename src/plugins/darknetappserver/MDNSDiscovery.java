/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */

package plugins.darknetappserver;

import freenet.clients.http.PageNode;
import freenet.config.Config;
import bcssl.BCModifiedSSL;
import freenet.crypt.ECDSA;
import freenet.pluginmanager.*;
import freenet.support.HTMLNode;
import freenet.support.api.HTTPRequest;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedList;

/**
 * This plugin implements Zeroconf (called Bonjour/RendezVous by apple) support on a Freenet node.
 * 
 * @author Florent Daigni&egrave;re &lt;nextgens@freenetproject.org&gt;
 *
 * @see http://www.dns-sd.org/ServiceTypes.html
 * @see http://www.multicastdns.org/
 * @see http://jmdns.sourceforge.net/
 * 
 * TODO: We shouldn't start a thread at all ... but they are issues on startup (the configuration framework isn't available yet)
 * TODO: Plug into config. callbacks to reflect changes @see #1217
 * TODO: Maybe we should make add forms onto that toadlet and let the user choose what to advertise or not 
 */
public class MDNSDiscovery implements FredPlugin, FredPluginHTTP, FredPluginRealVersioned {
	public static String freenetServiceType = "_freenet._udp.local.";
	private volatile  boolean goon = true;
	private JmDNS jmdns;
	private Config nodeConfig;
	private LinkedList ourAdvertisedServices, ourDisabledServices, foundNodes;
	private PluginRespirator pr;
	private static final long version = 2;
	/**
	 * Called upon plugin unloading : we unregister advertised services
	 */
	public synchronized void terminate() {
                try {
                    jmdns.close();
                } catch (IOException ex) {
                }
		goon = false;
		notifyAll();
	}


	public void runPlugin(PluginRespirator pr) {
		this.pr = pr;
		// wait until the node is initialised.
		while(pr.getNode() == null || !pr.getNode().isHasStarted()){
			try{
				Thread.sleep(1000);	
			}catch (InterruptedException e) {}			
		}
			
		nodeConfig = pr.getNode().config;
		ourAdvertisedServices = new LinkedList();
		ourDisabledServices = new LinkedList();
		foundNodes = new LinkedList();
		final ServiceInfo signedDarknetAppServerInfo;
		
		try{
			// Create the multicast listener
                      jmdns = JmDNS.create();

			final String address = "server -=" + pr.getNode().getMyName() + "=-";
			
			// Watch out for other nodes
			jmdns.addServiceListener(MDNSDiscovery.freenetServiceType, new NodeMDNSListener(this));
			
			// A signal containing signature, pubkey, pin (For DarknetAppServer Broadcast)
			String pinStr = "pin -="+BCModifiedSSL.getSelfSignedCertificatePin() + "=-";
			String shortData = truncateAndSanitize("Freenet 0.7 DarknetAppServer " + address);
			String data2sign = shortData + pinStr; 
                        ECDSA ecdsa;
                        ECDSA.Curves curves = ECDSA.Curves.P256;
                        ecdsa = new ECDSA(curves);
			byte[] signature = ecdsa.sign(data2sign.getBytes("UTF-8"));
			byte[] pubkey = ecdsa.getPublicKey().getEncoded();
			byte[] pin = pinStr.getBytes("UTF-8");
			byte[] signal = new byte[signature.length+4+pubkey.length+pin.length];
			signal[signal.length-1] = (byte) (signature.length%16);
			signal[signal.length-2] = (byte) (signature.length/16);
			signal[signal.length-3] = (byte) ((signature.length+pubkey.length)%16);
			signal[signal.length-4] = (byte) ((signature.length+pubkey.length)/16);
			int signEndPointer = signal[signal.length-2]*16 + signal[signal.length-1];
			int pubkeyEndPointer = signal[signal.length-4]*16 + signal[signal.length-3];
			for (int i=0;i!=signEndPointer;i++) {
				signal[i] = signature[i];
			}
			for (int i=signEndPointer;i!=pubkeyEndPointer;i++) {
				signal[i] = pubkey[i-signEndPointer];
			}
			for (int i=pubkeyEndPointer;i!=signal.length-4;i++) {
				signal[i] = pin[i-pubkeyEndPointer];
			}
			// Advertise DarknetAppServer to connect to Mobiles Apps
			int port = pr.getSubConfig().getInt("port");
			signedDarknetAppServerInfo = ServiceInfo.create("_darknetAppServer._tcp.local.", shortData,
				port, 0, 0, signal);
			/**
			* The commented code results in broadcast on one random network interface
			* But we need to broadcast on all active interfaces
			jmdns.registerService(signedDarknetAppServerInfo);
			ourAdvertisedServices.add(signedDarknetAppServerInfo);
			*/
			JmDNS[] services = new JmDNS[20]; //assuming less than 20 services
                        ServiceInfo[] serviceInfos = new ServiceInfo[20];
			Enumeration en = NetworkInterface.getNetworkInterfaces();
			int j = 0;
			while(en.hasMoreElements()) {
				NetworkInterface ni = (NetworkInterface)en.nextElement();
				Enumeration en2 = ni.getInetAddresses();
				if (ni.isUp() && !ni.isLoopback() && !ni.isVirtual()) {
					while (en2.hasMoreElements()) {
						InetAddress ia = (InetAddress)en2.nextElement();
                                                /**
                                                 * Get rid of the 6 to 4 addresses as they give rise to conflicting probes
                                                 */
						if (ia.getHostAddress().startsWith("2002:")) continue;
						services[j] = JmDNS.create(ia);
                                                serviceInfos[j] = signedDarknetAppServerInfo.clone();
						services[j].registerService(serviceInfos[j]);
						System.out.println("Advertising Darknet App Server On "+ ia.getHostAddress());
						j++;
					}
				}
			}
                        ourAdvertisedServices.add(signedDarknetAppServerInfo);

		} catch (IOException e) {
			e.printStackTrace();
		}

		while(goon){
			synchronized (this) {
				try{
					wait(Long.MAX_VALUE);
				}catch (InterruptedException e) {}	
			}
		}
	}

	private class NodeMDNSListener implements ServiceListener {
		final MDNSDiscovery plugin;
		
		public NodeMDNSListener(MDNSDiscovery plugin) {
			this.plugin = plugin;
		}
		
        public void serviceAdded(ServiceEvent event) {
            System.out.println("Service added   : " + event.getName()+"."+event.getType());
            // Force the gathering of informations
			jmdns.getServiceInfo(MDNSDiscovery.freenetServiceType, event.getName());
            synchronized (plugin) {
                plugin.notify();				
			}
        }
        
        public void serviceRemoved(ServiceEvent event) {
            System.out.println("Service removed : " + event.getName()+"."+event.getType());
            if(MDNSDiscovery.freenetServiceType.equals(event.getType())) {
            	synchronized (foundNodes) {
					Iterator it = foundNodes.iterator();
					ServiceInfo toRemove = event.getInfo();
					while(it.hasNext()) {
						ServiceInfo si = (ServiceInfo)it.next();
						if(toRemove.equals(si))
							foundNodes.remove(si);
					}
				}
            }
            synchronized (plugin) {
                plugin.notify();				
			}
        }
        
        public void serviceResolved(ServiceEvent event) {
            System.out.println("Service resolved: " + event.getInfo());
            if(MDNSDiscovery.freenetServiceType.equals(event.getType()))
            	synchronized (foundNodes) {
        			foundNodes.add(event.getInfo());				
            	}

            synchronized (plugin) {
                plugin.notify();				
			}
        }
    }
	
	private void PrintServices(HTMLNode contentNode, String description, ServiceInfo[] services)
	{
		HTMLNode peerTableInfobox = contentNode.addChild("div", "class", "infobox infobox-"+ (services.length > 0 ? "normal" : "warning"));
		HTMLNode peerTableInfoboxHeader = peerTableInfobox.addChild("div", "class", "infobox-header");
		HTMLNode peerTableInfoboxContent = peerTableInfobox.addChild("div", "class", "infobox-content");
		
		if(services != null && services.length > 0){
			peerTableInfoboxHeader.addChild("#", description);
			HTMLNode peerTable = peerTableInfoboxContent.addChild("table", "class", "darknet_connections");
			HTMLNode peerTableHeaderRow = peerTable.addChild("tr");
			peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { "The name or the  service.", "border-bottom: 1px dotted; cursor: help;" }, "Service Name");
			peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { "The name of the machine hosting the service.", "border-bottom: 1px dotted; cursor: help;" }, "Machine");
			peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { "The node's network address as IP:Port", "border-bottom: 1px dotted; cursor: help;" }, "Address");
			peerTableHeaderRow.addChild("th").addChild("span", new String[] { "title", "style" }, new String[] { "Service parameters", "border-bottom: 1px dotted; cursor: help;" }, "Parameters");
			
			HTMLNode peerRow;
			String mDNSService, mDNSServer, mDNSHost, mDNSPort, mDNSDescription;
			
			for(int i=0; i<services.length; i++){
			    peerRow = peerTable.addChild("tr");
			    ServiceInfo info = services[i];
			    mDNSService = info.getName();
				mDNSServer = info.getServer();
                                mDNSHost = "";
                                String[] hosts = null;
                                try {
                                     hosts = info.getHostAddresses();
                                } catch (NullPointerException e) {
                                }
                                if (hosts!=null) {
                                    for (String h : hosts) {
                                        mDNSHost = mDNSHost+" " +h;
                                    }
                                }
				mDNSPort = Integer.toString(info.getPort());
				mDNSDescription = info.getNiceTextString();
				
				peerRow.addChild("td", "class", "peer-name").addChild("#", (mDNSService == null ? "null" : mDNSService));
				peerRow.addChild("td", "class", "peer-machine").addChild("#", (mDNSServer == null ? "null" : mDNSServer));
				peerRow.addChild("td", "class", "peer-address").addChild("#", (mDNSHost == null ? "null" : mDNSHost) + ':' + (mDNSPort == null ? "null": mDNSPort));
				peerRow.addChild("td", "class", "peer-private-darknet-comment-note").addChild("#", (mDNSDescription == null ? "" : mDNSDescription));
			}
		}else{
			peerTableInfoboxHeader.addChild("#", description);
			peerTableInfoboxContent.addChild("#", "No Freenet resources found on the local subnet, sorry!");
		}
	}
		
	public String handleHTTPGet(HTTPRequest request) throws PluginHTTPException {
		PageNode page = pr.getPageMaker().getPageNode("Configuration page", false, null);
		HTMLNode pageNode = page.outer;
		HTMLNode contentNode = page.content;

		PrintServices(contentNode, "The following services are being broadcast from this node :", (ServiceInfo[])ourAdvertisedServices.toArray(new ServiceInfo[ourAdvertisedServices.size()]));
		
		synchronized (foundNodes) {
			if(foundNodes.size() > 0)
				PrintServices(contentNode, "The following nodes have been found on the local subnet :", (ServiceInfo[])foundNodes.toArray(new ServiceInfo[foundNodes.size()]));
		}
		
		if(ourAdvertisedServices.size() < 3){
			HTMLNode disabledServicesInfobox = contentNode.addChild("div", "class", "infobox infobox-normal");
			HTMLNode disabledServicesInfoboxHeader = disabledServicesInfobox.addChild("div", "class", "infobox-header");
			HTMLNode disabledServicesInfoboxContent = disabledServicesInfobox.addChild("div", "class", "infobox-content");

			disabledServicesInfoboxHeader.addChild("#", "Disabled services");
			
			disabledServicesInfoboxContent.addChild("#", "The following services won't be advertised on the local network" +
					" because they are either disabled ot bound to the local interface :");
			
			HTMLNode disabledServicesList = disabledServicesInfoboxContent.addChild("ul", "id", "disabled-service-list");
			
			for(int i=0; i<ourDisabledServices.size(); i++)
				disabledServicesList.addChild("li").addChild("#", ((ServiceInfo) ourDisabledServices.get(i)).getName());
		}
		
		return pageNode.generate();
	}
	
	public String handleHTTPPost(HTTPRequest request) throws PluginHTTPException {
		return null;
	}
	
	/**
	 * Function used to sanitize a service name (it ought to be less than 63 char. long and shouldn't contain '.')
	 * @param The string to sanitize
	 * @return a sanitized String
	 */
	private String truncateAndSanitize(String str)
	{
		int indexOfDot; 
		do{
			indexOfDot = str.indexOf('.');
			if(indexOfDot == -1) break;
			str = str.substring(0, indexOfDot) + ',' +str.substring(indexOfDot + 1);
		} while(true);
		
		if(str.length() > 62)
			str = str.substring(0, 62);
		return str;
	}

	public long getRealVersion() {
		return version;
	}
}
