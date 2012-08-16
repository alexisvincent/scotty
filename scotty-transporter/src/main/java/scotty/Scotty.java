package scotty;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy.Type;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.swing.JFrame;
import javax.swing.JOptionPane;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.owasp.webscarab.httpclient.HTTPClientFactory;
import org.owasp.webscarab.model.Preferences;
import org.owasp.webscarab.plugin.CredentialManager;
import org.owasp.webscarab.plugin.CredentialManagerUI;
import org.owasp.webscarab.plugin.Framework;
import org.owasp.webscarab.plugin.proxy.ListenerSpec;
import org.owasp.webscarab.plugin.proxy.Proxy;
import org.owasp.webscarab.plugin.proxy.ProxyPlugin;
import org.owasp.webscarab.ui.swing.CredentialRequestDialog;

import scotty.crypto.CryptoException;
import scotty.crypto.KeyManager;
import scotty.event.EventDispatcher;
import scotty.event.EventObserver;
import scotty.event.Events;
import scotty.plugin.TransformingProxyPlugin;
import scotty.transformer.RequestTransformer;
import scotty.transformer.ResponseTransformer;
import scotty.transformer.impl.DefaultRequestTransformer;
import scotty.transformer.impl.DefaultResponseTransformer;
import scotty.ui.SystrayIndicatorProxyPlugin;
import scotty.ui.SystrayManager;
import scotty.util.Messages;

import com.btr.proxy.search.ProxySearch;

/**
 * Main class. scotty, transporter of freedom.
 * 
 * Usage: scotty [-g <Gateway-URL>] [-p <Local-Port>] [ -d ] [ -proxyHost <proxyHost> -proxyPort <Port> ]<br />
 * <br />
 * CLI Params: <br />
 * <br />
 * -g Gateway-url wg. http://my.gatew.ay/gate.php <br />
 * -p local Port eg. 8008 <br />
 * -d disables gateway usage, no value<br />
 * -proxyHost proxy Host (eg. my.pro.xy)
 * -proxyPort proxy Port (eg. 8080)
 * 
 * @author flo
 * 
 */
public class Scotty implements EventObserver {
	
	
	
	private static Logger log = Logger.getLogger(Scotty.class.getName());

	private JFrame parent = new JFrame();

	/**
	 * Use gateway - if false, scotty acts transparent as proxy.
	 */
	public static boolean useGateway = true;

	/**
	 * Use encryption - if false, scotty will not encrypt requests
	 */
	public static boolean disableEncryption = false;

	/**
	 * Timeout for the RSA Token, default: 1 hour
	 */
	public static long tokenTimeout = 3600;

	/**
	 * CLI to disable the gateway usage
	 */
	private static String DONT_USE_GATEWAY = "d";

	/**
	 * CLI to specify gateway.
	 */
	private static final String GATEWAY_CMDLINE_PARAM = "g";

	/**
	 * CLI to specify the local port
	 */
	private static final String LOCALPORT_CMDLINE_PARAM = "p";

	/**
	 * CLI for creating a new key pair
	 */
	private static final String CREATEKEY_CMDLINE_PARAM = "c";

	/**
	 * CLI for proxy host
	 */
	private static final String PROXY_HOST_CMDLINE_PARAM = "proxyHost";
	
	/**
	 * CLI for proxy port
	 */
	private static final String PROXY_PORT_CMDLINE_PARAM = "proxyPort";
	
	/**
	 * CLI for creating a new key pair
	 */
	private static final String TOKENTIMEOUT_CMDLINE_PARAM = "timeout";

	/**
	 * CLI to specify gateway.
	 */
	private static final String DISABLE_ENCRYPTION_CMDLINE_PARAM = "disableencryption";

	/**
	 * CLI for private key
	 */
	private static final String PRIVATEKEY_CMDLINE_PARAM = "privatekey";

	/**
	 * CLI for public key
	 */
	private static final String PUBLICKEY_CMDLINE_PARAM = "publickey";

	/**
	 * CLI for gateways public key
	 */
	private static final String GATEWAYSPUBLICKEY_CMDLINE_PARAM = "gatewayspublickey";

	/**
	 * default private key
	 */
	private static final String DEFAULT_PRIVATEKEY = "resources:defaultprivatekey";

	/**
	 * default public key
	 */
	private static final String DEFAULT_PUBLICKEY = "resources:defaultpublickey";

	/**
	 * default gateways key
	 */
	private static final String DEFAULT_GATEWAYSPUBLICKEY = "resources:gatewaydefaultpublickey";

	/**
	 * Key Manager for RSA Key Access
	 */
	private static KeyManager keyManager;

	/**
	 * Default gateway url, if none is specified.
	 */
	private String defaultGatewayUrl = "http://localhost";

	/**
	 * gateway to use.
	 */
	public static String gatewayUrl;

	/**
	 * Local port of scotty.
	 */
	private String localPort;

	/**
	 * Local address, scotty binds to.
	 */
	private String localAddr = "127.0.0.1";

	/**
	 * Default local port.
	 */
	private String defaultLocalPort = "8008";

	/**
	 * WebScarab framework.
	 */
	private Framework framework;

	private SystrayManager systray = new SystrayManager();

	private Messages msgs = new Messages();

	/**
	 * Set if, proxy-vole should try to autoconfig the proxy. (If cmdline args are not used).
	 */
	private boolean autoConfigProxy = true;
	
	public Scotty() {
		EventDispatcher.add(this);
	}

	/**
	 * Initialize Scotty.
	 * 
	 * @param args
	 *            Arguments
	 * @throws Exception
	 *             Exception.
	 */
	public static void main(String[] args) throws Exception {
		Scotty scotty = new Scotty();
		CommandLine commandLine = scotty.handleCommandline(args);

		// generate keys
		if (commandLine.hasOption(CREATEKEY_CMDLINE_PARAM)) {
			generateKeyPair();

			// start scotty
		} else {

			// load keys
			loadKeys(commandLine);

			// start server
			scotty.init();
		}

	}

	private static void generateKeyPair() throws IOException, Exception,
			CryptoException {
		BufferedReader commandLineInput = new BufferedReader(
				new InputStreamReader(System.in));
		System.out.print("filename of private key: ");
		String privateKeyFile = commandLineInput.readLine();

		System.out.print("filename of public key: ");
		String publicKeyFile = commandLineInput.readLine();

		System.out.print("password for private key: ");
		String privateKeyPassword = new String(System.console().readPassword());

		KeyManager keyManager = new KeyManager();
		keyManager.generateKeyPair();
		System.out.println("key pair successfully generated");

		keyManager.writePrivateKey(privateKeyFile, privateKeyPassword);
		System.out.println("private key successfully saved");

		keyManager.writePublicKey(publicKeyFile);
		System.out.println("public key successfully saved");

		// check parsing files
		KeyManager checkKeyManager = new KeyManager();
		checkKeyManager.readPrivateKey(privateKeyFile, privateKeyPassword);
		if (checkKeyManager.getPrivateKey() != null)
			System.out.println("private key successfully read");
		else
			System.err.println("can't read generated private key file");

		checkKeyManager.readPublicKey(publicKeyFile);
		if (checkKeyManager.getPublicKey() != null)
			System.out.println("public key successfully read");
		else
			System.err.println("can't read generated public key file");
	}

	/**
	 * Parses the commandline and sets the configs.
	 * 
	 * @param args
	 *            args.
	 * @throws Exception
	 *             exc.
	 */
	private CommandLine handleCommandline(String[] args) throws Exception {
		Options opts = new Options();
		opts.addOption(GATEWAY_CMDLINE_PARAM, true, "URL of the Gateway");
		opts.addOption(LOCALPORT_CMDLINE_PARAM, true,
				"Local port, where scotty listens for requests");
		opts.addOption(CREATEKEY_CMDLINE_PARAM, false, "Create new KeyPair");
		opts.addOption(PRIVATEKEY_CMDLINE_PARAM, true, "private key");
		opts.addOption(PUBLICKEY_CMDLINE_PARAM, true, "public key");
		opts.addOption(DISABLE_ENCRYPTION_CMDLINE_PARAM, false,
				"disable encryption");
		opts.addOption(TOKENTIMEOUT_CMDLINE_PARAM, true,
				"timeout for the RSA token (default = 1.5 hours)");

		opts.addOption(DONT_USE_GATEWAY, false,
				"Don't use gateway - direct connection.");
		opts.addOption(PROXY_HOST_CMDLINE_PARAM, true,
				"Proxy Host");
		opts.addOption(PROXY_PORT_CMDLINE_PARAM, true,
				"Proxy Port");		
		
		CommandLineParser cmd = new PosixParser();
		CommandLine line = cmd.parse(opts, args);
		if ( line.hasOption(PROXY_HOST_CMDLINE_PARAM) && line.hasOption(PROXY_PORT_CMDLINE_PARAM)) {
			setHttpProxy(line.getOptionValue(PROXY_HOST_CMDLINE_PARAM), Integer.valueOf(line.getOptionValue(PROXY_PORT_CMDLINE_PARAM)));
			setHttpsProxy(line.getOptionValue(PROXY_HOST_CMDLINE_PARAM), Integer.valueOf(line.getOptionValue(PROXY_PORT_CMDLINE_PARAM)));
			autoConfigProxy = false;
		}
		
		gatewayUrl = line.getOptionValue(GATEWAY_CMDLINE_PARAM,
				defaultGatewayUrl);

		localPort = line.getOptionValue(LOCALPORT_CMDLINE_PARAM,
				defaultLocalPort);
		Preferences.setPreference("Proxy.listeners", localAddr + ":"
				+ localPort);

		if (line.hasOption(DONT_USE_GATEWAY)) {
			useGateway = false;
		}

		if (line.hasOption(DISABLE_ENCRYPTION_CMDLINE_PARAM)) {
			disableEncryption = true;
		}

		return line;
	}

	public void init() throws Exception {
		systray.setTooltip(msgs.scotty() + " " + gatewayUrl);

		configureProxySettings();
		framework = new Framework();
		
		framework.setSession(null, null, null);
		Preferences.setPreference("WebScarab.promptForCredentials", "true");
		CredentialManager cm = framework.getCredentialManager();
		CredentialManagerUI credentialRequestDialog = new CredentialRequestDialog(
				null, true, cm);
		cm.setUI(credentialRequestDialog);

		loadMainPlugin(framework);

		Thread.currentThread().join();
	}

	/**
	 * Loads the main plugin {@link TransformingProxyPlugin}. Starts the local
	 * proxy server.
	 * 
	 * @param framework
	 *            WebScarab Framework.
	 */
	public void loadMainPlugin(Framework framework) {
		Proxy proxy = new Proxy(framework);
		framework.addPlugin(proxy);

		RequestTransformer requestTransformer = new DefaultRequestTransformer(keyManager, disableEncryption);
		ResponseTransformer responseTransformer = new DefaultResponseTransformer(keyManager, disableEncryption);
		TransformingProxyPlugin cp = new TransformingProxyPlugin(requestTransformer, responseTransformer);
		proxy.addPlugin(cp);

		ProxyPlugin indicator = new SystrayIndicatorProxyPlugin(systray);
		proxy.addPlugin(indicator);

		for (ListenerSpec spec : proxy.getProxies()) {
			proxy.addListener(spec);
		}
	}

	/**
	 * Eventhandler for Systray Icon
	 * 
	 * @param event
	 *            exit event
	 * @param o
	 *            optional param
	 */
	@Override
	public void eventReceived(Events event, Object o) {
		if (event.equals(Events.EXIT)) {
			try {
				framework.stopPlugins();
			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				System.exit(0);
			}
		}
	}

	/**
	 * Load Keys for RSA Encryption
	 * 
	 * @param commandLine
	 * @throws CryptoException
	 */
	public static void loadKeys(CommandLine commandLine) throws CryptoException {
		keyManager = new KeyManager();

		if (commandLine.hasOption(PRIVATEKEY_CMDLINE_PARAM))
			keyManager.readPrivateKey(
					commandLine.getOptionValue(PRIVATEKEY_CMDLINE_PARAM), "");
		else
			keyManager.readPrivateKey(DEFAULT_PRIVATEKEY, "");

		if (commandLine.hasOption(PUBLICKEY_CMDLINE_PARAM))
			keyManager.readPublicKey(commandLine
					.getOptionValue(PUBLICKEY_CMDLINE_PARAM));
		else
			keyManager.readPublicKey(DEFAULT_PUBLICKEY);

		if (commandLine.hasOption(GATEWAYSPUBLICKEY_CMDLINE_PARAM))
			keyManager.readGatewaysPublicKey(commandLine
					.getOptionValue(GATEWAYSPUBLICKEY_CMDLINE_PARAM));
		else
			keyManager.readGatewaysPublicKey(DEFAULT_GATEWAYSPUBLICKEY);

		if (commandLine.hasOption(TOKENTIMEOUT_CMDLINE_PARAM)) {
			tokenTimeout = Long.parseLong(commandLine
					.getOptionValue(TOKENTIMEOUT_CMDLINE_PARAM));
		}
		keyManager.setMaxValidityOfToken(tokenTimeout);
	}

	/**
	 * Configures the Proxy Settings to access the gatway, uses system settings
	 * by utilising proxy-vole.
	 */
	public void configureProxySettings() {
		if ( !autoConfigProxy ) return;
		
		try {
			ProxySearch proxySearch = ProxySearch.getDefaultProxySearch();
			ProxySelector myProxySelector = proxySearch.getProxySelector();

			ProxySelector.setDefault(myProxySelector);

			List<java.net.Proxy> proxies = ProxySelector.getDefault().select(
					new URI(gatewayUrl));
			if (proxies.size() > 0) {
				HTTPClientFactory factory = HTTPClientFactory.getInstance();
				for (java.net.Proxy p : proxies) {
					InetSocketAddress address = (InetSocketAddress) p.address();

					if (Type.HTTP == p.type()) {
						setHttpProxy(address.getHostName(), address.getPort());
					} else if (Type.SOCKS == p.type()) {
						setHttpsProxy(address.getHostName(), address.getPort());
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			JOptionPane.showMessageDialog(parent,
					"Proxy could not be set! Try it manually",
					"Proxy not resolved", JOptionPane.WARNING_MESSAGE);

		}

	}

	public void setHttpProxy(String host, Integer port) {
		HTTPClientFactory factory = HTTPClientFactory.getInstance();
		factory.setHttpProxy(host, Integer.valueOf(port));
		Preferences .setPreference( "WebScarab.httpProxy", host + ":" + port);
	}
	
	public void setHttpsProxy(String host, Integer port) {
		HTTPClientFactory factory = HTTPClientFactory.getInstance();
		factory.setHttpsProxy(host, Integer.valueOf(port));
		Preferences	.setPreference("WebScarab.httpsProxy", host + ":" + port);
	}
	
}