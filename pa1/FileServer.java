package pa1;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Logger;

import pa1.autograding.GradingServer;
import pa1.util.BlockRequest;
import pa1.util.ClientStateMap;
import pa1.util.SharedServerInfo;

/**
 * @author V. Arun
 */

/*
 * This class is the main TCP server. It uses non-blocking IO
 * to simultaneously listen on a number of ports. The different
 * ports serve as "peers". The ports change randomly over time
 * in order to prevent "cheating" strategies based on remembering
 * ports from previous runs. Different ports are rate-limited
 * at different (deterministically) random rates.
 */
public class FileServer extends AbstractNIOServer {

	private static final long MAINTENANCE_INTERVAL = 30000; // ms
	private static final int STRETCH_DURATION = 200; //Config
	// .getStretchDuration();
	private static final long DEPRECATION_DELAY = 5000; // ms
	private static final long FORCE_DEPRECATION_DELAY = 1800000; // ms
	private static final int RANDOM_SEED =
			(int) (Math.random() * Integer.MAX_VALUE);
	private static long lastMaintenanceTime = System.currentTimeMillis();

	private final BlockTransportScheduler manager;
	private static final SharedServerInfo sharedInfo = new SharedServerInfo(); // Shared by TorrentServer
	private TreeMap<Long, ServerSocketChannel> deprecated =
			new TreeMap<Long, ServerSocketChannel>();
	private final ClientStateMap clientMap = new ClientStateMap();
	private final Logger log = Config.setLogHandler(getClass());

	public FileServer(Integer[] ports) throws IOException {
		super(new HashSet<Integer>(Arrays.asList(ports)));
		sharedInfo.addPorts(this.portToServers.keySet());
		(manager = new BlockTransportScheduler()).start();
		Config.setLogHandler(log, getClass());
		log.info("Finished initiating " + getClass().getName() + " on " +
				Arrays.asList(Config.DEFAULT_SERVER_PORTS));
	}

	@Override
	protected void processAcceptedConnection(SocketChannel connChannel) {
		if (!this.checkAcceptable(connChannel))
			this.closeRandomClientChannel(connChannel);
		this.clientMap.register(connChannel);
		this.doMaintenanceActivities();
	}

	private void closeRandomClientChannel(SocketChannel connChannel) {
		SocketChannel randomChannel = this.clientMap.getRandomOpenClientChannel(
				connChannel);
		if(randomChannel!=null) this.closeClientChannel(randomChannel);
	}

	@Override
	protected void executeRequest(String request, SocketChannel connChannel) {

		try {
			if (this.badRequestHandler(request, connChannel)) return;
			// else everything below

			this.updateStats(request, connChannel);

			// generate request
			BlockRequest blockRequest =
					new BlockRequest(
							FileManager.getChunkNumber(request),
							connChannel,
							getServiceRate(connChannel.socket().getLocalPort()),
							GradingServer.getScrambledFile(request),
							FileManager.isHeaderRequest(request));

			// delegate to request manager
			if (blockRequest != null) manager.deposit(blockRequest);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	protected boolean closeClientChannel(SocketChannel sockChannel,
			String message) {
		ByteBuffer buf = ByteBuffer.wrap(message.getBytes());
		try {
			sockChannel.write(buf);
		} catch (IOException e) {
			log.info("IOException while closing connection: " + e.getMessage());
		}
		return this.closeClientChannel(sockChannel);
	}

	protected boolean closeClientChannel(SocketChannel sockChannel) {
		this.clientMap.unRegister(sockChannel);
		return super.closeClientChannel(sockChannel);
	}

	protected static SharedServerInfo getSharedInfo() {
		return sharedInfo;
	}

	protected ClientStateMap ClientStateMap() {
		return this.clientMap;
	}

	private void updateStats(String request, SocketChannel channel) {
		this.updateTestingStats(request, channel);
		this.updateClientIPStats(request, channel);
	}

	private void updateTestingStats(String request, SocketChannel channel) {
		GradingServer.testingMap.processRequest(request, channel);
	}

	private void updateClientIPStats(String request, SocketChannel channel) {
		String IP = channel.socket().getInetAddress().getHostAddress();
		if (IP == null) return;
		this.clientMap.processRequest(IP, request);
	}

	private boolean checkAcceptable(SocketChannel channel) {
		String ip = channel.socket().getInetAddress().getHostAddress();
		if (ip == null) return false;
		return this.clientMap.isAcceptable(ip);
	}

	private boolean badRequestHandler(String request, SocketChannel channel)
			throws IOException {
		if (FileManager.isLegitRequest(request) &&
				!FileManager.isLegitTestingRequest(request)) return false;
		if (this.handleUnregisteredTestingRequest(request, channel))
			return true;
		if (this.handleBadFormat(request, channel)) return true;
		return false;
	}

	private boolean handleUnregisteredTestingRequest(String request,
			SocketChannel channel) {
		if (FileManager.isLegitTestingRequest(request) &&
				(GradingServer.getScrambledFile(request) == null)) {
			this.closeClientChannel(channel,
				Config.StatusCode.BAD_FORMAT.getStatusCode() + ": " +
						GradingServer.NO_TEST_INITIATED_MESSAGE);
			return true;
		}
		return false;
	}

	private boolean handleBadFormat(String request, SocketChannel channel) {
		if (FileManager.isLegitRequest(request)) return false;
		log.info("Received bad request <" + request + "> of length " +
				request.length() + " on " + channel);
		closeClientChannel(channel,
			Config.StatusCode.BAD_FORMAT.getStatusCode() + ": <" + request +
					">\n");
		return false;

	}

	private boolean doMaintenanceActivities() {
		boolean allCompleted = true;
		if (!testAndSetMaintenanceTime()) return allCompleted;
		String logMsg = "";
		int port = 0;
		try {
			// add random new port
			while (sharedInfo.getPorts().size() <= Config.DEFAULT_SERVER_PORTS.length &&
					!addServer(port = sharedInfo.getRandomNewPort()))
				log.warning("Failed to add server on port " + port);
			logMsg += ("Added new port " + port + "; ");

			// remove random existing port if enough open ports
			while (sharedInfo.getPorts().size() > Config.DEFAULT_SERVER_PORTS.length &&
					!removeServer(port = sharedInfo.getRandomListeningPort()))
				log.warning("Failed to remove server on port " + port);
			logMsg += ("Deprecated existing port " + port + "; ");

			// try to close deprecated servers
			Set<Integer> closed = closeDeprecatedServers();
			logMsg += ("Tried closing deprecated servers, closed " + closed);
		} catch (IOException e) {
			e.printStackTrace();
			allCompleted = false;
		}
		log.info(logMsg);
		return allCompleted;
	}

	private boolean testAndSetMaintenanceTime() {
		if (System.currentTimeMillis() - lastMaintenanceTime < MAINTENANCE_INTERVAL)
			return false;
		lastMaintenanceTime = System.currentTimeMillis();
		return true;
	}

	private boolean canAddServerPort(int port) {
		return sharedInfo.canAddPort(port);
	}

	private boolean addServer(int port) throws IOException {
		boolean added = false;
		if (this.canAddServerPort(port)) {
			added = (this.openServer(port) != null);
			if (port != Config.DEFAULT_FIXED_PORT) sharedInfo.addPort(port);
		}
		return added;
	}

	private boolean removeServer(int port) {
		if (!this.portToServers.containsKey(port) ||
				port==Config.DEFAULT_SERVER_PORTS[0]) return false;
		sharedInfo.deprecatePort(port);
		// Closing server socket does not affect extant connections
		this.deprecate(this.portToServers.get(port));
		return true;
	}

	private void deprecate(ServerSocketChannel ssChannel) {
		this.deprecated.put(System.currentTimeMillis(), ssChannel);
	}

	private boolean canCloseDeprecatedServer(ServerSocketChannel sc,
			long deprecatedTime) {
		if (System.currentTimeMillis() - deprecatedTime < DEPRECATION_DELAY)
			return false;
		else if (System.currentTimeMillis() - deprecatedTime > FORCE_DEPRECATION_DELAY)
			return true;
		// check if ongoing requests are being serviced
		if (this.manager.checkOngoing(this.serverToConnections.get(sc)))
			return false;
		return true;
	}

	private Set<Integer> closeDeprecatedServers() throws IOException {
		if (this.deprecated.isEmpty()) return null;
		Set<Integer> closed = new HashSet<Integer>();
		Iterator<Entry<Long, ServerSocketChannel>> iterator =
				this.deprecated.entrySet().iterator();
		while (iterator.hasNext()) {
			Entry<Long, ServerSocketChannel> entry = iterator.next();
			if (canCloseDeprecatedServer(entry.getValue(), entry.getKey())) {
				ServerSocketChannel ssc = entry.getValue();
				int port =
						((InetSocketAddress) ssc.getLocalAddress()).getPort();
				ssc.close();
				closed.add(port);
				iterator.remove();
			}
			else break; // stop as all other (sorted) entries
		}
		return closed;
	}

	private static final int getRandomServiceRate(int port) {
		return Math.abs((((int) (RANDOM_SEED ^ port)) % STRETCH_DURATION));
	}

	private static final int getServiceRate(int port) {
		if (port == Config.DEFAULT_SERVER_PORTS[0])
			return (int) (STRETCH_DURATION * 0.8);
		else return getRandomServiceRate(port);
	}

	public static void main(String[] args) {

		try {
			FileServer fs = new FileServer(Config.DEFAULT_SERVER_PORTS);
			fs.run();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
