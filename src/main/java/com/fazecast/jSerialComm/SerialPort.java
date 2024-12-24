/*
 * SerialPort.java
 *
 *       Created on:  Feb 25, 2012
 *  Last Updated on:  Apr 11, 2024
 *           Author:  Will Hedgecock
 *
 * Copyright (C) 2012-2024 Fazecast, Inc.
 *
 * This file is part of jSerialComm.
 *
 * jSerialComm is free software: you can redistribute it and/or modify
 * it under the terms of either the Apache Software License, version 2, or
 * the GNU Lesser General Public License as published by the Free Software
 * Foundation, version 3 or above.
 *
 * jSerialComm is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *
 * You should have received a copy of both the GNU Lesser General Public
 * License and the Apache Software License along with jSerialComm. If not,
 * see <http://www.gnu.org/licenses/> and <http://www.apache.org/licenses/>.
 */

package com.fazecast.jSerialComm;

import com.fazecast.jSerialComm.android.AndroidPort;

import java.lang.ProcessBuilder;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class provides native access to serial ports and devices without requiring external libraries or tools.
 *
 * @see java.io.InputStream
 * @see java.io.OutputStream
 */
@SuppressWarnings({"unused"})
public class SerialPort
{
	// Parity Values
	static final public int NO_PARITY = 0;
	static final public int ODD_PARITY = 1;
	static final public int EVEN_PARITY = 2;
	static final public int MARK_PARITY = 3;
	static final public int SPACE_PARITY = 4;

	// Number of Stop Bits
	static final public int ONE_STOP_BIT = 1;
	static final public int ONE_POINT_FIVE_STOP_BITS = 2;
	static final public int TWO_STOP_BITS = 3;

	// Flow Control constants
	static final public int FLOW_CONTROL_DISABLED = 0x00000000;
	static final public int FLOW_CONTROL_RTS_ENABLED = 0x00000001;
	static final public int FLOW_CONTROL_CTS_ENABLED = 0x00000010;
	static final public int FLOW_CONTROL_DSR_ENABLED = 0x00000100;
	static final public int FLOW_CONTROL_DTR_ENABLED = 0x00001000;
	static final public int FLOW_CONTROL_XONXOFF_IN_ENABLED = 0x00010000;
	static final public int FLOW_CONTROL_XONXOFF_OUT_ENABLED = 0x00100000;

	// Timeout Modes
	static final public int TIMEOUT_NONBLOCKING = 0x00000000;
	static final public int TIMEOUT_READ_SEMI_BLOCKING = 0x00000001;
	static final public int TIMEOUT_READ_BLOCKING = 0x00000010;
	static final public int TIMEOUT_WRITE_BLOCKING = 0x00000100;
	static final public int TIMEOUT_SCANNER = 0x00001000;

	// Serial Port Listening Events
	static final public int LISTENING_EVENT_TIMED_OUT = 0x00000000;
	static final public int LISTENING_EVENT_DATA_AVAILABLE = 0x00000001;
	static final public int LISTENING_EVENT_DATA_RECEIVED = 0x00000010;
	static final public int LISTENING_EVENT_DATA_WRITTEN = 0x00000100;
	static final public int LISTENING_EVENT_BREAK_INTERRUPT = 0x00010000;
	static final public int LISTENING_EVENT_CARRIER_DETECT = 0x00020000;
	static final public int LISTENING_EVENT_CTS = 0x00040000;
	static final public int LISTENING_EVENT_DSR = 0x00080000;
	static final public int LISTENING_EVENT_RING_INDICATOR = 0x00100000;
	static final public int LISTENING_EVENT_FRAMING_ERROR = 0x00200000;
	static final public int LISTENING_EVENT_FIRMWARE_OVERRUN_ERROR = 0x00400000;
	static final public int LISTENING_EVENT_SOFTWARE_OVERRUN_ERROR = 0x00800000;
	static final public int LISTENING_EVENT_PARITY_ERROR = 0x01000000;
	static final public int LISTENING_EVENT_PORT_DISCONNECTED = 0x10000000;

	// Static initializer loads correct native library for this machine
	static private final ReentrantLock libraryLock = new ReentrantLock(true);
	static private final String versionString = "2.11.1";
	static private final String tmpdirAppIdProperty = "fazecast.jSerialComm.appid";
	static private final List<Thread> shutdownHooks = new ArrayList<Thread>();
	static private boolean cleanUpOnShutdown = false, allowOpenForEnumeration = false, isAndroidDelete = false;
	static private boolean isWindows = false, isAndroid = false;
	static private volatile boolean isShuttingDown = false;
	static
	{
		try
		{
			// Determine the temporary file directories for native library storage
			String[] architectures;
			String libraryPath, libraryFileName, extractedFileName;
			final String manualLibraryPath = System.getProperty("jSerialComm.library.path", "");
			final String OS = System.getProperty("os.name").toLowerCase();
			final String arch = System.getProperty("os.arch").toLowerCase();
			final File tempFileDirectory = new File(System.getProperty("java.io.tmpdir"), "jSerialComm" + File.separator + System.getProperty(tmpdirAppIdProperty, ".") + File.separator + versionString).getCanonicalFile();
			final File userHomeDirectory = new File(System.getProperty("user.home"), ".jSerialComm" + File.separator + System.getProperty(tmpdirAppIdProperty, ".") + File.separator + versionString).getCanonicalFile();
			final boolean randomizeNativeName = System.getProperty("jSerialComm.library.randomizeNativeName", "false").equalsIgnoreCase("true");
			cleanUpDirectory(new File(tempFileDirectory, ".."));
			cleanUpDirectory(new File(userHomeDirectory, ".."));

			// Determine Operating System and architecture
			if (System.getProperty("java.vm.vendor").toLowerCase().contains("android"))
			{
				isAndroidDelete = true;
				libraryPath = "Android";
				libraryFileName = "libjSerialComm.so";
				architectures = new String[] { "arm64-v8a", "armeabi-v7a", "x86_64", "x86" };
			}
			else if (OS.contains("win"))
			{
				isWindows = true;
				libraryPath = "Windows";
				libraryFileName = "jSerialComm.dll";
				architectures = new String[] { "aarch64", "armv7", "x86_64", "x86" };
			}
			else if (OS.contains("mac"))
			{
				libraryPath = "OSX";
				libraryFileName = "libjSerialComm.jnilib";
				architectures = new String[] { "aarch64", "x86_64", "x86" };
			}
			else if ((OS.contains("sunos")) || (OS.contains("solaris")))
			{
				libraryPath = "Solaris";
				libraryFileName = "libjSerialComm.so";
				architectures = new String[] { "sparcv9_64", "sparcv8plus_32", "x86_64", "x86" };
			}
			else if (OS.contains("freebsd"))
			{
				libraryPath = "FreeBSD";
				libraryFileName = "libjSerialComm.so";
				architectures = new String[] { "arm64", "x86_64", "x86" };
			}
			else if (OS.contains("openbsd"))
			{
				libraryPath = "OpenBSD";
				libraryFileName = "libjSerialComm.so";
				architectures = new String[] { "amd64", "x86" };
			}
			else if ((OS.contains("nix")) || (OS.contains("nux")))
			{
				libraryPath = "Linux";
				libraryFileName = "libjSerialComm.so";
				if (!System.getProperty("os.arch_full", "").isEmpty())
					architectures = new String[] { System.getProperty("os.arch_full").toLowerCase() };
				else if (arch.contains("86") || arch.contains("amd"))
					architectures = new String[] { "x86_64", "x86", "armv5", "armv6hf", "armv7hf", "armv8_64", "armv8_32", "ppc64le" };
				else
					architectures = new String[] { "armv5", "armv6hf", "armv7hf", "armv8_64", "x86_64", "armv8_32", "ppc64le", "x86" };
			}
			else
			{
				System.err.println("This operating system is not supported by the jSerialComm library.");
				libraryPath = libraryFileName = null;
				architectures = null;
				System.exit(-1);
			}

			// Load platform-specific binaries for non-Android systems
			if (!isAndroid)
			{
				// Attempt to load from a manually-specified user location
				boolean libraryLoaded = false;
				Vector<String> errorMessages = new Vector<String>();
				if (!manualLibraryPath.isEmpty())
				{
					for (int i = 0; !libraryLoaded && (i < architectures.length); ++i)
						libraryLoaded = loadNativeLibrary(new File(manualLibraryPath, libraryPath + File.separator + architectures[i] + File.separator + libraryFileName).getCanonicalPath(), errorMessages);
					if (!libraryLoaded)
						libraryLoaded = loadNativeLibrary(new File(manualLibraryPath, libraryFileName).getCanonicalPath(), errorMessages);
				}

				// Attempt to load from the system-defined library location
				try
				{
					System.loadLibrary("jSerialComm");
					if (getNativeLibraryVersion().equals(versionString))
						libraryLoaded = true;
					else
					{
						errorMessages.add("Native library in system path has the incorrect version: " + getNativeLibraryVersion() + ", Expected " + versionString);
						uninitializeLibrary();
					}
				}
				catch (UnsatisfiedLinkError e) { errorMessages.add(e.getMessage()); }
				catch (Exception e) { errorMessages.add(e.getMessage()); }

				// Attempt to load from an existing extracted location
				for (int attempt = 0; !libraryLoaded && (attempt < 2); ++attempt)
				{
					File nativeLibrary = new File((attempt == 0) ? tempFileDirectory : userHomeDirectory, libraryFileName);
					libraryLoaded = nativeLibrary.exists() && loadNativeLibrary(nativeLibrary.getCanonicalPath(), errorMessages);
				}

				// Attempt to load from the expected JAR location
				extractedFileName = randomizeNativeName ? ((new Date()).getTime() + "-" + libraryFileName) : libraryFileName;
				for (int attempt = 0; !libraryLoaded && (attempt < 2); ++attempt)
				{
					// Create a temporary working directory with open permissions
					deleteDirectory(new File((attempt == 0) ? tempFileDirectory : userHomeDirectory, "..").getCanonicalFile());
					File tempNativeLibrary = new File((attempt == 0) ? tempFileDirectory : userHomeDirectory, extractedFileName);
					if (tempNativeLibrary.getParentFile().exists() || tempNativeLibrary.getParentFile().mkdirs())
					{
						tempNativeLibrary.getParentFile().setReadable(true, false);
						tempNativeLibrary.getParentFile().setWritable(true, true);
						tempNativeLibrary.getParentFile().setExecutable(true, false);
					}
					else
						continue;

					// Attempt to load the native jSerialComm library for any available architecture
					for (int i = 0; !libraryLoaded && (i < architectures.length); ++i)
					{
						InputStream fileContents = SerialPort.class.getResourceAsStream("/" + libraryPath + "/" + architectures[i] + "/" + libraryFileName);
						if (fileContents != null)
							try
							{
								// Copy the native library to the temporary working directory
								tempNativeLibrary.delete();
								FileOutputStream destinationFileContents = new FileOutputStream(tempNativeLibrary);
								byte[] transferBuffer = new byte[4096];
								int numBytesRead;
								while ((numBytesRead = fileContents.read(transferBuffer)) > 0)
									destinationFileContents.write(transferBuffer, 0, numBytesRead);
								destinationFileContents.close();
								fileContents.close();
								tempNativeLibrary.setReadable(true, false);
								tempNativeLibrary.setWritable(false, false);
								tempNativeLibrary.setExecutable(true, false);

								// Attempt to load the native library
								errorMessages.add("Loading for arch: " + architectures[i]);
								libraryLoaded = loadNativeLibrary(tempNativeLibrary.getCanonicalPath(), errorMessages);
								if (libraryLoaded)
									errorMessages.add("Successfully loaded!");
							}
							catch (Exception e) { e.printStackTrace(); }
					}

					// Throw an error if unable to load any native libraries
					if (!libraryLoaded)
					{
						tempNativeLibrary.delete();
						if (attempt > 0)
						{
							StringBuilder errorMessage = new StringBuilder("Cannot load native library. Errors as follows:\n");
							for (int i = 0; i < errorMessages.size(); ++i)
								errorMessage.append('[').append(i+1).append("]: ").append(errorMessages.get(i)).append('\n');
							throw new UnsatisfiedLinkError(errorMessage.toString());
						}
					}
				}
			}

			// Add a shutdown hook to ensure that all ports get closed
			Runtime.getRuntime().addShutdownHook(SerialPortThreadFactory.get().newThread(new Runnable()
			{
				@Override
				public void run()
				{
					// Run any user-specified shutdown hooks
					try {
						for (Thread hook : shutdownHooks)
						{
							hook.start();
							hook.join();
						}
					}
					catch (InterruptedException ignored) {}

					// Un-initialize the native library
					isShuttingDown = true;
					if (!isAndroid)
						uninitializeLibrary();

					// Optionally delete all native library files
					if (cleanUpOnShutdown)
						try
						{
							deleteDirectory(new File(tempFileDirectory, "..").getCanonicalFile());
							deleteDirectory(new File(userHomeDirectory, "..").getCanonicalFile());
						}
						catch (IOException ignored) {}
				}
			}));
		}
		catch (IOException e) { e.printStackTrace(); }
	}

	// Static symbolic link testing function
	static private boolean isSymbolicLink(File file) throws IOException
	{
		File canonicalFile = (file.getParent() == null) ? file : new File(file.getParentFile().getCanonicalFile(), file.getName());
		return !canonicalFile.getCanonicalFile().equals(canonicalFile.getAbsoluteFile());
	}

	// Static recursive directory cleanup function
	static private void cleanUpDirectory(File path) throws IOException
	{
		// Clean up all files that are not in a directory named after the current library version
		if (path.isDirectory())
		{
			File[] files = path.listFiles();
			if (files != null)
				for (File file : files)
					if (!file.getName().equals(versionString))
						cleanUpDirectory(file.getCanonicalFile());
		}
		if (!path.getName().equals(versionString) && !path.getName().equals(".."))
			path.delete();
	}

	// Static recursive directory deletion function
	static private void deleteDirectory(File path)
	{
		// Recursively delete all files and directories
		if (path.isDirectory())
		{
			File[] files = path.listFiles();
			if (files != null)
				for (File file : files)
					deleteDirectory(file);
		}
		path.delete();
	}

	// Static native library loading function
	static private boolean loadNativeLibrary(String absoluteLibraryPath, Vector<String> errorMessages)
	{
		try
		{
			System.load(absoluteLibraryPath);
			if (!getNativeLibraryVersion().equals(versionString))
			{
				errorMessages.add("Native library at " + absoluteLibraryPath + " has the incorrect version: " + getNativeLibraryVersion() + ", Expected " + versionString);
				uninitializeLibrary();
				return false;
			}
			else
				return true;
		}
		catch (UnsatisfiedLinkError e) { errorMessages.add(e.getMessage()); return false; }
		catch (Exception e) { errorMessages.add(e.getMessage()); return false; }
	}

	/**
	 * Returns the same output as calling {@link #getPortDescription()}.  This may be useful for display containers which call a Java Object's default toString() method.
	 *
	 * @return The port description as reported by the device itself.
	 */
	@Override
	public final String toString() { return getPortDescription(); }

	/**
	 * Returns the current version of the jSerialComm library.
	 *
	 * @return The current library version.
	 */
	static public String getVersion() { return versionString; }

	/**
	 * Registers a shutdown hook that will run just before the application fully closes, due to either
	 * exiting normally or in response to a user interrupt such as Ctrl+C.
	 * <p>
	 * These hooks can be used to carry out any final serial port operations that should be executed to
	 * ensure graceful disconnection or device shutdown.
	 * <p>
	 * There is no need to add a shutdown hook just to close all open ports, as this is done automatically
	 * by the library; however, any special reads, writes, or port handling that should take place prior
	 * to closing of the ports should be handled in a shutdown hook registered with this method.
	 *
	 * @param hook A {@link java.lang.Thread} object that will run just before the application shuts down.
	 * @see java.lang.Runtime#addShutdownHook(Thread)
	 * @see java.lang.Thread
	 */
	static public void addShutdownHook(Thread hook)
	{
		libraryLock.lock();
		try { shutdownHooks.add(hook); }
		finally { libraryLock.unlock(); }
	}

	/**
	 * Registers an application context to be used by the library when running on an Android device.
	 * <p>
	 * This method <b>must</b> be called prior to using any other library functionality when running on
	 * an Android device. Failure to do so will result in a runtime error. Calling this method on a
	 * non-Android device will have no effect.
	 * <p>
	 * The preferred way to use this method is by calling "SerialPort.setAndroidContext(getApplication())"
	 * from your Android application's "onCreate()" method.
	 * 
	 * @param androidApp The android.app.Application object for the current application.
	 * @return Whether the library was able to successfully register itself with the Android context
	 */
	static public boolean setAndroidContext(Object androidApp)
	{
		if (isAndroid)
		{
			try
			{
				if (Class.forName("android.app.Application").isInstance(androidApp))
				{
					AndroidPort.setAndroidContext(androidApp);
					return true;
				}
			}
			catch (ClassNotFoundException e) {}
		}
		return false;
	}

	/**
	 * Causes the library to attempt to clean up after itself upon shutdown and automatically delete
	 * all temporary files it may have created.
	 * <p>
	 * It is not recommended to use this function, as its use will increase the overhead of unpacking
	 * the library and searching for the correct machine architecture every time the library is loaded;
	 * however, if you have a specific need to ensure that temporary files are removed (for example, if
	 * you are using a dynamically generated "App ID" with the "fazecast.jSerialComm.appid" Java
	 * property, then this functionality might make sense.
	 */
	static public void autoCleanupAtShutdown()
	{
		cleanUpOnShutdown = true;
	}

	/**
	 * Allows the library to open a port during enumeration to retrieve additional details about its 
	 * serial number, manufacturer, and description. Currently, this only affects enumeration of
	 * FTDI-specific devices on Windows.
	 * <p>
	 * It is not recommended to use this function, as its use will increase the overhead of enumerating
	 * devices on Windows; however, if you have a specific need to ensure that FTDI-specific device info
	 * is returned by the library, then this functionality might make sense.
	 */
	static public void allowPortOpenForEnumeration()
	{
		allowOpenForEnumeration = true;
	}

	/**
	 * Returns a list of all available serial ports on this machine.
	 * <p>
	 * The serial ports can be accessed by iterating through each of the SerialPort objects in this array.
	 * <p>
	 * Note that the array will also include any serial ports that your application currently has open, even if
	 * the devices attached to those ports become disconnected. As such, it is important that you always call
	 * {@link #closePort()} on a SerialPort object if it becomes disconnected, which is detectable by inspecting
	 * the return values from the various read calls or by registering a {@link SerialPortDataListener} for the 
	 * {@link SerialPort#LISTENING_EVENT_PORT_DISCONNECTED} event.
	 * <p>
	 * The {@link #openPort()} method must be called before any attempts to read from or write to the port.
	 * Likewise, {@link #closePort()} should be called when you are finished accessing the port.
	 * <p>
	 * Note that repeated calls to this function will re-enumerate all serial ports and will return a completely
	 * unique set of array objects. As such, you should store a reference to the serial port object(s) you are
	 * interested in in your own application code.
	 * <p>
	 * All serial port parameters or timeouts can be changed at any time after the port has been opened.
	 *
	 * @return An array of {@link SerialPort} objects.
	 */
	static public SerialPort[] getCommPorts()
	{
		libraryLock.lock();
		try { return isAndroid ? AndroidPort.getCommPortsNative() : getCommPortsNative(); }
		finally { libraryLock.unlock(); }
	}

	/**
	 * Allocates a {@link SerialPort} object corresponding to the user-specified port descriptor.
	 * <p>
	 * On Windows machines, this descriptor should be in the form of "COM[*]".<br>
	 * On Linux machines, the descriptor will look similar to "/dev/tty[*]".<br>
	 * On Android machines, this method is unavailable and will return <b>null</b>.
	 *
	 * @param portDescriptor The desired serial port to use with this library.
	 * @return A {@link SerialPort} object.
	 * @throws SerialPortInvalidPortException If a {@link SerialPort} object cannot be created due to a logical or formatting error in the portDescriptor parameter.
	 */
	static public SerialPort getCommPort(String portDescriptor) throws SerialPortInvalidPortException
	{
		// Make this method a no-op on Android
		if (isAndroid)
			return null;

		// Correct port descriptor, if needed
		libraryLock.lock();
		try
		{
			// Resolve home directory ~
			if (portDescriptor.startsWith("~" + File.separator))
				portDescriptor = System.getProperty("user.home") + portDescriptor.substring(1);

			// See what kind of descriptor was passed in
			if (isWindows)
				portDescriptor = "\\\\.\\" + portDescriptor.substring(portDescriptor.lastIndexOf('\\')+1);
			else if (isSymbolicLink(new File(portDescriptor)))
				portDescriptor = (new File(portDescriptor)).getCanonicalPath();
			else if (!((new File(portDescriptor)).exists()))
			{
				// Attempt to locate the correct port descriptor
				portDescriptor = "/dev/" + portDescriptor;
				if (!((new File(portDescriptor)).exists()))
					portDescriptor = "/dev/" + portDescriptor.substring(portDescriptor.lastIndexOf('/')+1);

				// Check if the updated port descriptor exists
				if (!((new File(portDescriptor)).exists()))
					throw new IOException();
			}

			// Create the SerialPort object
			SerialPort serialPort = new SerialPort();
			serialPort.comPort = portDescriptor;
			serialPort.vendorID = serialPort.productID = -1;
			serialPort.friendlyName = "User-Specified Port";
			serialPort.portDescription = "User-Specified Port";
			serialPort.serialNumber = "Unknown";
			serialPort.manufacturer = "Unknown";
			serialPort.portLocation = "0-0";
			serialPort.retrievePortDetails();
			return serialPort;
		}
		catch (Exception e) { throw new SerialPortInvalidPortException("Unable to create a serial port object from the invalid port descriptor: " + portDescriptor, e); }
		finally { libraryLock.unlock(); }
	}

	// Serial Port Parameters (may also be accessed by native code)
	private AndroidPort androidPort = null;
	private volatile long portHandle = 0;
	private volatile int baudRate = 9600, dataBits = 8, stopBits = SerialPort.ONE_STOP_BIT, parity = SerialPort.NO_PARITY, eventFlags = 0;
	private volatile int timeoutMode = SerialPort.TIMEOUT_NONBLOCKING, readTimeout = 0, writeTimeout = 0, flowControl = 0;
	private volatile int sendDeviceQueueSize = 4096, receiveDeviceQueueSize = 4096, vendorID, productID;
	private volatile int safetySleepTimeMS = 200, rs485DelayBefore = 0, rs485DelayAfter = 0;
	private volatile byte xonStartChar = 17, xoffStopChar = 19;
	private volatile SerialPortDataListener userDataListener = null;
	private volatile SerialPortEventListener serialEventListener = null;
	private volatile String comPort, friendlyName, portDescription, portLocation, serialNumber, manufacturer;
	private volatile boolean eventListenerRunning = false, disableConfig = false, disableExclusiveLock = false;
	private volatile boolean rs485Mode = false, rs485ActiveHigh = true, rs485RxDuringTx = false, rs485EnableTermination = false;
	private volatile boolean isRtsEnabled = true, isDtrEnabled = true, autoFlushIOBuffers = false, requestElevatedPermissions = false;
	private final ReentrantLock configurationLock = new ReentrantLock(true);

	/**
	 * Opens this serial port for reading and writing with an optional delay time and user-specified device buffer size.
	 * <p>
	 * All serial port parameters or timeouts can be changed at any time before or after the port has been opened.
	 * <p>
	 * Note that when trying to open a Serial-over-Bluetooth port, this method may fail if the underlying device driver takes too
	 * long to connect. In that case, retrying this method may allow it to eventually connect and succeed.
	 * <p>
	 * Note that calling this method on an already opened port will simply reconfigure the port parameters.
	 *
	 * @param safetySleepTime The number of milliseconds to sleep before opening the port in case of frequent closing/openings.
	 * @param deviceSendQueueSize The requested size in bytes of the internal device driver's output queue (no effect on OSX)
	 * @param deviceReceiveQueueSize The requested size in bytes of the internal device driver's input queue (no effect on Linux/OSX)
	 * @return Whether the port was successfully opened with a valid configuration.
	 */
	public final boolean openPort(int safetySleepTime, int deviceSendQueueSize, int deviceReceiveQueueSize)
	{
		configurationLock.lock();
		try
		{
			// Set the send/receive internal buffer sizes, and return true if already opened
			safetySleepTimeMS = safetySleepTime;
			if (deviceSendQueueSize > 0)
				sendDeviceQueueSize = deviceSendQueueSize;
			if (deviceReceiveQueueSize > 0)
				receiveDeviceQueueSize = deviceReceiveQueueSize;
			if (portHandle != 0)
				return (androidPort != null) ? androidPort.configPort(this) : configPort(portHandle);

			// Force a sleep to ensure that the port does not become unusable due to rapid closing/opening on the part of the user
			if (safetySleepTimeMS > 0)
				try { Thread.sleep(safetySleepTimeMS); } catch (Exception e) { Thread.currentThread().interrupt(); }

			// If this is an Android root application, we must explicitly allow serial port access to the library
			File portFile = isAndroidDelete ? new File(comPort) : null;
			if (portFile != null && (!portFile.canRead() || !portFile.canWrite()))
			{
				Process process = null;
				try
				{
					process = Runtime.getRuntime().exec("su");
					DataOutputStream writer = new DataOutputStream(process.getOutputStream());
					writer.writeBytes("chmod 666 " + comPort + "\n");
					writer.writeBytes("exit\n");
					writer.flush();
					BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
					while (reader.readLine() != null);
				}
				catch (Exception e)
				{
					e.printStackTrace();
					return false;
				}
				finally
				{
					if (process == null)
						return false;
					try { process.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
					try { process.getInputStream().close(); } catch (IOException e) { e.printStackTrace(); return false; }
					try { process.getOutputStream().close(); } catch (IOException e) { e.printStackTrace(); return false; }
					try { process.getErrorStream().close(); } catch (IOException e) { e.printStackTrace(); return false; }
					try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
				}
			}

			// Natively open the serial port, and start an event-based listener if registered
			portHandle = (androidPort != null) ? androidPort.openPortNative(this) : openPortNative();
			if ((portHandle != 0) && (serialEventListener != null))
				serialEventListener.startListening();
			return (portHandle != 0);
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Opens this serial port for reading and writing with an optional delay time.
	 * <p>
	 * All serial port parameters or timeouts can be changed at any time before or after the port has been opened.
	 * <p>
	 * Note that when trying to open a Serial-over-Bluetooth port, this method may fail if the underlying device driver takes too
	 * long to connect. In that case, retrying this method may allow it to eventually connect and succeed.
	 * <p>
	 * Note that calling this method on an already opened port will simply reconfigure the port parameters.
	 *
	 * @param safetySleepTime The number of milliseconds to sleep before opening the port in case of frequent closing/openings.
	 * @return Whether the port was successfully opened with a valid configuration.
	 */
	public final boolean openPort(int safetySleepTime)
	{
		return openPort(safetySleepTime, sendDeviceQueueSize, receiveDeviceQueueSize);
	}

	/**
	 * Opens this serial port for reading and writing.
	 * <p>
	 * This method is equivalent to calling {@link #openPort} with a <i>safetySleepTime</i> of 0.
	 * <p>
	 * All serial port parameters or timeouts can be changed at any time before or after the port has been opened.
	 * <p>
	 * Note that when trying to open a Serial-over-Bluetooth port, this method may fail if the underlying device driver takes too
	 * long to connect. In that case, retrying this method may allow it to eventually connect and succeed.
	 * <p>
	 * Note that calling this method on an already opened port will simply reconfigure the port parameters.
	 *
	 * @return Whether the port was successfully opened with a valid configuration.
	 */
	public final boolean openPort()
	{
		return openPort(0);
	}

	/**
	 * Closes this serial port.
	 * <p>
	 * Note that calling this method on an already closed port will simply return a value of true.
	 *
	 * @return Whether the port was successfully closed.
	 */
	public final boolean closePort()
	{
		configurationLock.lock();
		try
		{
			// Stop a registered event listener
			if (serialEventListener != null)
				serialEventListener.stopListening();

			// Natively close the port
			if (portHandle != 0)
				portHandle = (androidPort != null) ? androidPort.closePortNative() : closePortNative(portHandle);
			return (portHandle == 0);
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Returns whether the port is currently open and available for communication.
	 * <p>
	 * <b>Warning!</b> This method alone will <b>not</b> reliably indicate the unplugging of a USB serial port,
	 * including USB ports on popular microcontrollers like Arduino and ESP32. It will continue to return
	 * <i>true</i> after you open the port until you explicitly close the port using {@link #closePort()},
	 * even if the interface has been unplugged.
	 * <p>
	 * In order to detect when a port is unplugged, use the following code:
	 * <pre>
	 * port.addDataListener(new SerialPortDataListener() {
	 *    {@literal @}Override
	 *    public int getListeningEvents() {
	 *       return SerialPort.LISTENING_EVENT_PORT_DISCONNECTED;
	 *       // or, if you want to listen for multiple events:
	 *       // return SerialPort.LISTENING_EVENT_PORT_DISCONNECTED | SerialPort.LISTENING_EVENT_DATA_RECEIVED;
	 *    }
	 *    {@literal @}Override
	 *    public void serialEvent(SerialPortEvent serialPortEvent) {
	 *       if (serialPortEvent.getEventType() == SerialPort.LISTENING_EVENT_PORT_DISCONNECTED)
	 *          port.closePort();
	 *       else if (serialPortEvent.getEventType() == SerialPort.LISTENING_EVENT_DATA_RECEIVED)
	 *          // ... you get the idea
	 *    }
	 * });
	 * </pre>
	 * <p>
	 * Once the port has been closed by the event handler, {@link #isOpen()} will return false,
	 * and you can periodically attempt to reopen it within your event loop without creating a new
	 * {@link SerialPort} object:
	 * <pre>
	 * if (!port.isOpen())
	 *    port.open();
	 * </pre>
	 * <p>
	 * Note that once a USB serial port has been unplugged and reconnected, it's unlikely to work again until the
	 * port's {@link #closePort()} and {@link openPort(int, int, int)} methods have both been called to
	 * re-establish the connection.
	 *
	 * @return Whether the port is opened.
	 */
	public final boolean isOpen()
	{
		configurationLock.lock();
		try { return (portHandle != 0); }
		finally { configurationLock.unlock(); }
	}

	/**
	 * Disables the library from calling any of the underlying device driver configuration methods.
	 * <p>
	 * This function should never be called except in very specific cases involving USB-to-Serial converters
	 * with buggy device drivers. In that case, this function <b>must</b> be called before attempting to
	 * open the port.
	 */
	public final void disablePortConfiguration()
	{
		configurationLock.lock();
		try { disableConfig = true; }
		finally { configurationLock.unlock(); }
	}

	/**
	 * Disables the library from obtaining an exclusive lock on the serial port.
	 * <p>
	 * This function should never be called except on very specific systems which do not support obtaining
	 * exclusive locks on system resources.
	 */
	public final void disableExclusiveLock()
	{
		configurationLock.lock();
		try { disableExclusiveLock = true; }
		finally { configurationLock.unlock(); }
	}

	/**
	 * Allows the library to request elevation of the current user's permissions for use in making certain
	 * system-specific changes regarding this serial port.
	 * <p>
	 * Examples of such changes include reducing the default latency for FTDI-type devices using
	 * the Windows registry, or adding the current Linux user to the same OS group to which the serial
	 * port belongs so that they can access the port without having to make these changes manually.
	 * <p>
	 * On Windows, if elevated permissions are required, a User Access Control (UAC) dialog box will
	 * appear, requesting authorization to carry out the privileged operation.
	 * On a non-Windows system, elevated permissions will be requested as if you had used the 'sudo' command
	 * in a terminal. As such, this function should not be used if your application does not contain or use
	 * a console.
	 * <p>
	 * Care should be taken when choosing to use this function as it <i>may</i> cause a prompt to appear
	 * during runtime of your final application requesting permission to make these elevated changes which
	 * may detract from the user experience of your application. When possible, making any system changes
	 * related to serial port usage should be done manually before attempting to use such ports, but in some
	 * situations, this function may make it easier for your application to automatically apply these
	 * necessary changes.
	 */
	public final void allowElevatedPermissionsRequest()
	{
		configurationLock.lock();
		try { requestElevatedPermissions = true; }
		finally { configurationLock.unlock(); }
	}

	/**
	 * Returns the source code line location of the latest error encountered during execution of
	 * the native code for this port.
	 * <p>
	 * This function must be called as soon as an error is encountered, or it may return an incorrect source
	 * code line location.
	 * 
	 * @return Source line of latest native code error.
	 */
	public final int getLastErrorLocation()
	{
		return (androidPort != null) ? androidPort.getLastErrorLocation() : getLastErrorLocation(portHandle);
	}

	/**
	 * Returns the error number returned by the most recent native source code line that failed execution.
	 * <p>
	 * This function must be called as soon as an error is encountered, or it may return an incorrect or
	 * invalid error code.
	 * 
	 * @return Error number of the latest native code error.
	 */
	public final int getLastErrorCode()
	{
		return (androidPort != null) ? androidPort.getLastErrorCode(): getLastErrorCode(portHandle);
	}

	// Serial Port Native Methods
	private static native void uninitializeLibrary();					// Un-initializes the JNI code
	private static native SerialPort[] getCommPortsNative();            // Enumerate available serial ports
	private static native String getNativeLibraryVersion();				// Returns the version string of the currently loaded native library
	private native void retrievePortDetails();							// Retrieves port descriptions, names, and details
	private native long openPortNative();								// Opens serial port
	private native long closePortNative(long portHandle);				// Closes serial port
	private native boolean configPort(long portHandle);					// Changes/sets serial port parameters as defined by this class
	private native boolean flushRxTxBuffers(long portHandle);			// Flushes underlying RX/TX device buffers
	private native int waitForEvent(long portHandle);					// Waits for serial event to occur as specified in eventFlags
	private native int bytesAvailable(long portHandle);					// Returns number of bytes available for reading
	private native int bytesAwaitingWrite(long portHandle);				// Returns number of bytes still waiting to be written
	private native int readBytes(long portHandle, byte[] buffer, int bytesToRead, int offset, int timeoutMode, int readTimeout);	// Reads bytes from serial port
	private native int writeBytes(long portHandle, byte[] buffer, int bytesToWrite, int offset, int timeoutMode);	// Write bytes to serial port
	private native void setEventListeningStatus(long portHandle, boolean eventListenerRunning);	// Change event listener running flag in native code
	private native boolean setBreak(long portHandle);					// Set BREAK status on serial line
	private native boolean clearBreak(long portHandle);					// Clear BREAK status on serial line
	private native boolean setRTS(long portHandle);						// Set RTS line to 1
	private native boolean clearRTS(long portHandle);					// Clear RTS line to 0
	private native boolean setDTR(long portHandle);						// Set DTR line to 1
	private native boolean clearDTR(long portHandle);					// Clear DTR line to 0
	private native boolean getCTS(long portHandle);						// Returns whether the CTS signal is 1
	private native boolean getDSR(long portHandle);						// Returns whether the DSR signal is 1
	private native boolean getDCD(long portHandle);						// Returns whether the DCD signal is 1
	private native boolean getDTR(long portHandle);						// Returns whether the DTR signal is 1
	private native boolean getRTS(long portHandle);						// Returns whether the RTS signal is 1
	private native boolean getRI(long portHandle);						// Returns whether the RI signal is 1
	private native int getLastErrorLocation(long portHandle);			// Returns the source code line location of the latest native code error
	private native int getLastErrorCode(long portHandle);				// Returns the errno value of the latest native code error

	/**
	 * Returns the number of bytes available without blocking if {@link #readBytes(byte[], int)} were to be called immediately
	 * after this method returns.
	 *
	 * @return The number of bytes currently available to be read, or -1 if the port is not open.
	 */
	public final int bytesAvailable() { return (portHandle != 0) ? ((androidPort != null) ? androidPort.bytesAvailable() : bytesAvailable(portHandle)) : -1; }

	/**
	 * Returns the number of bytes still waiting to be written in the device's output queue.
	 * <p>
	 * Note that this method is not required or guaranteed to be implemented by the underlying device driver. Use it carefully and test your application to ensure it is working as you expect.
	 *
	 * @return The number of bytes currently waiting to be written, or -1 if the port is not open.
	 */
	public final int bytesAwaitingWrite() { return (portHandle != 0) ? ((androidPort != null) ? androidPort.bytesAwaitingWrite() : bytesAwaitingWrite(portHandle)) : -1; }

	/**
	 * Reads up to <i>bytesToRead</i> raw data bytes from the serial port and stores them in the buffer starting at the indicated offset.
	 * <p>
	 * The length of the byte buffer minus the offset must be greater than or equal to the value passed in for <i>bytesToRead</i>. If not, a value of -2 will be returned.
	 * <p>
	 * In blocking-read mode, if no timeouts were specified or the read timeout was set to 0, this call will block until <i>bytesToRead</i> bytes of data have been successfully
	 * read from the serial port. Otherwise, this method will return after <i>bytesToRead</i> bytes of data have been read or the number of milliseconds specified by the read timeout
	 * have elapsed, whichever comes first, regardless of the availability of more data.
	 *
	 * @param buffer The buffer into which the raw data is read.
	 * @param bytesToRead The number of bytes to read from the serial port.
	 * @param offset The read buffer index into which to begin storing data.
	 * @return The number of bytes successfully read, -1 if there was a reading error, or -2 if there was a buffer-size error.
	 */
	public final int readBytes(byte[] buffer, int bytesToRead, int offset)
	{
		// Ensure that the buffer is large enough to hold the requested number of bytes
		if (bytesToRead > (buffer.length - offset))
			return -2;

		// Read all requested bytes from native code
		return (portHandle != 0) ? ((androidPort != null) ? androidPort.readBytes(buffer, bytesToRead, offset, timeoutMode, readTimeout) : readBytes(portHandle, buffer, bytesToRead, offset, timeoutMode, readTimeout)) : -1;
	}

	/**
	 * Reads up to <i>bytesToRead</i> raw data bytes from the serial port and stores them in the buffer.
	 * <p>
	 * The length of the byte buffer must be greater than or equal to the value passed in for <i>bytesToRead</i>
	 * <p>
	 * In blocking-read mode, if no timeouts were specified or the read timeout was set to 0, this call will block until <i>bytesToRead</i> bytes of data have been successfully
	 * read from the serial port. Otherwise, this method will return after <i>bytesToRead</i> bytes of data have been read or the number of milliseconds specified by the read timeout
	 * have elapsed, whichever comes first, regardless of the availability of more data.
	 *
	 * @param buffer The buffer into which the raw data is read.
	 * @param bytesToRead The number of bytes to read from the serial port.
	 * @return The number of bytes successfully read, or -1 if there was an error reading from the port.
	 */
	public final int readBytes(byte[] buffer, int bytesToRead) { return readBytes(buffer, bytesToRead, 0); }

	/**
	 * Writes up to <i>bytesToWrite</i> raw data bytes from the buffer parameter to the serial port starting at the indicated offset.
	 * <p>
	 * The length of the byte buffer minus the offset must be greater than or equal to the value passed in for <i>bytesToWrite</i>. If not, a value of -2 will be returned.
	 * <p>
	 * In blocking-write mode, this call will block until <i>bytesToWrite</i> bytes of data have been successfully written to the serial port. Otherwise, this method will return
	 * after <i>bytesToWrite</i> bytes of data have been written to the device driver's internal data buffer, which, in most cases, should be almost instantaneous unless the data
	 * buffer is full.
	 *
	 * @param buffer The buffer containing the raw data to write to the serial port.
	 * @param bytesToWrite The number of bytes to write to the serial port.
	 * @param offset The buffer index from which to begin writing to the serial port.
	 * @return The number of bytes successfully written, -1 if there was a writing error, or -2 if there was a buffer-size error.
	 */
	public final int writeBytes(byte[] buffer, int bytesToWrite, int offset)
	{
		// Ensure that the buffer is large enough to write the requested number of bytes
		if (bytesToWrite > (buffer.length - offset))
			return -2;

		// Write to the serial port until all bytes have been consumed
		int totalNumWritten = 0;
		while ((portHandle != 0) && (totalNumWritten != bytesToWrite))
		{
			int numWritten = (androidPort != null) ? androidPort.writeBytes(buffer, bytesToWrite - totalNumWritten, offset + totalNumWritten, timeoutMode) :
				writeBytes(portHandle, buffer, bytesToWrite - totalNumWritten, offset + totalNumWritten, timeoutMode);
			if (numWritten > 0)
				totalNumWritten += numWritten;
			else
				break;
		}
		return ((portHandle != 0) && (totalNumWritten >= 0)) ? totalNumWritten : -1;
	}

	/**
	 * Writes up to <i>bytesToWrite</i> raw data bytes from the buffer parameter to the serial port.
	 * <p>
	 * The length of the byte buffer must be greater than or equal to the value passed in for <i>bytesToWrite</i>
	 * <p>
	 * In blocking-write mode, this call will block until <i>bytesToWrite</i> bytes of data have been successfully written to the serial port. Otherwise, this method will return
	 * after <i>bytesToWrite</i> bytes of data have been written to the device driver's internal data buffer, which, in most cases, should be almost instantaneous unless the data
	 * buffer is full.
	 *
	 * @param buffer The buffer containing the raw data to write to the serial port.
	 * @param bytesToWrite The number of bytes to write to the serial port.
	 * @return The number of bytes successfully written, or -1 if there was an error writing to the port.
	 */
	public final int writeBytes(byte[] buffer, int bytesToWrite) { return writeBytes(buffer, bytesToWrite, 0); }

	/**
	 * Returns the underlying transmit buffer size used by the serial port device driver. The device or operating system may choose to misrepresent this value.
	 * <p>
	 * Only Windows and Linux-based operating systems are potentially able to return a correct value for this method. On other operating systems, this value is undefined.
	 * 
	 * @return The underlying device transmit buffer size.
	 */
	public final int getDeviceWriteBufferSize() { return sendDeviceQueueSize; }

	/**
	 * Returns the underlying receive buffer size used by the serial port device driver. The device or operating system may choose to misrepresent this value.
	 * <p>
	 * Only Windows and Linux-based operating systems are potentially able to return a correct value for this method. On other operating systems, this value is undefined.
	 * 
	 * @return The underlying device receive buffer size.
	 */
	public final int getDeviceReadBufferSize() { return receiveDeviceQueueSize; }

	/**
	 * Sets the BREAK signal on the serial control line.
	 * 
	 * @return true if successful, false if not.
	 */
	public final boolean setBreak() { return (portHandle != 0) && ((androidPort != null) ? androidPort.setBreak() : setBreak(portHandle)); }

	/**
	 * Clears the BREAK signal from the serial control line.
	 * 
	 * @return true if successful, false if not.
	 */
	public final boolean clearBreak() { return (portHandle != 0) && ((androidPort != null) ? androidPort.clearBreak() : clearBreak(portHandle)); }

	/**
	 * Asserts RTS by setting the line's state to 1.
	 * <ul>
	 *   <li>On a "real" RS232 port, setting RTS causes the pin to output a positive voltage.</li>
	 *   <li>With a typical USB-UART bridge (like the CP210x), the RTS pin is "active low," so setting RTS causes the pin to go LOW.</li>
	 * </ul>
	 * 
	 * @return true if successful, false if not.
	 */
	public final boolean setRTS()
	{
		isRtsEnabled = true;
		return (androidPort != null) ? androidPort.setRTS() : ((portHandle == 0) || setRTS(portHandle));
	}

	/**
	 * De-asserts RTS by clearing the line's state to 0.
	 * <ul>
	 *   <li>On a "real" RS232 port, clearing RTS causes the pin to output a negative voltage.</li>
	 *   <li>With a typical USB-UART bridge (like the CP210x), RTS is "active low," so clearing RTS causes the pin to go HIGH.</li>
	 * </ul>
	 * 
	 * @return true if successful, false if not.
	 */
	public final boolean clearRTS()
	{
		isRtsEnabled = false;
		return (androidPort != null) ? androidPort.clearRTS() : ((portHandle == 0) || clearRTS(portHandle));
	}

	/**
	 * Asserts DTR by setting the line's state to 1.
	 * <ul>
	 *   <li>On a "real" RS232 port, asserting DTR causes the pin to output a positive voltage.</li>
	 *   <li>With a typical USB-UART bridge (like the CP210x), DTR is "active low," so setting DTR causes the pin to go LOW.</li>
	 * </ul>
	 * 
	 * @return true if successful, false if not.
	 */
	public final boolean setDTR()
	{
		isDtrEnabled = true;
		return (androidPort != null) ? androidPort.setDTR() : ((portHandle == 0) || setDTR(portHandle));
	}

	/**
	 * De-asserts DTR by clearing the line's state to 0.
	 * <ul>
	 *   <li>On a "real" RS232 port, clearing DTR causes the pin to output a negative voltage.</li>
	 *   <li>With a typical USB-UART bridge (like the CP210x), the DTR pin is "active low," so clearing DTR causes the pin to go HIGH.</li>
	 * </ul>
	 * <p>
	 * If you call this function <i>before</i> opening your port, it may help to mitigate a known problem with Arduino-based
	 * devices resetting themselves upon a connection being made.
	 *
	 * @return true if successful, false if not.
	 */
	public final boolean clearDTR()
	{
		isDtrEnabled = false;
		return (androidPort != null) ? androidPort.clearDTR() : ((portHandle == 0) || clearDTR(portHandle));
	}

	/**
	 * Returns whether the CTS line is currently asserted.
	 * @return Whether or not the CTS line is asserted.
	 */
	public final boolean getCTS() { return (portHandle != 0) && ((androidPort != null) ? androidPort.getCTS() : getCTS(portHandle)); }

	/**
	 * Returns whether the DSR line is currently asserted.
	 * @return Whether or not the DSR line is asserted.
	 */
	public final boolean getDSR() { return (portHandle != 0) && ((androidPort != null) ? androidPort.getDSR() : getDSR(portHandle)); }

	/**
	 * Returns whether the DCD line is currently asserted.
	 * @return Whether or not the DCD line is asserted.
	 */
	public final boolean getDCD() { return (portHandle != 0) && ((androidPort != null) ? androidPort.getDCD() : getDCD(portHandle)); }

	/**
	 * Returns whether the DTR line is currently asserted.
	 * <p>
	 * Note that polling this line's status is not supported on Windows, so results may be incorrect.
	 * @return Whether or not the DTR line is asserted.
	 */
	public final boolean getDTR() { return (portHandle != 0) && ((androidPort != null) ? androidPort.getDTR() : getDTR(portHandle)); }

	/**
	 * Returns whether the RTS line is currently asserted.
	 * <p>
	 * Note that polling this line's status is not supported on Windows, so results may be incorrect.
	 * @return Whether or not the RTS line is asserted.
	 */
	public final boolean getRTS() { return (portHandle != 0) && ((androidPort != null) ? androidPort.getRTS() : getRTS(portHandle)); }

	/**
	 * Returns whether the RI line is currently asserted.
	 * @return Whether or not the RI line is asserted.
	 */
	public final boolean getRI() { return (portHandle != 0) && ((androidPort != null) ? androidPort.getRI() : getRI(portHandle)); }

	// SerialPort Constructors
	private SerialPort() {}
	private SerialPort(String port, String friendly, String description, String location, String serial, String manufacture, int vid, int pid)
	{
		comPort = port;
		friendlyName = friendly;
		portDescription = description;
		portLocation = location;
		serialNumber = serial;
		manufacturer = manufacture;
		vendorID = vid;
		productID = pid;
	}

	/**
	 * Adds a {@link SerialPortDataListener} to the serial port interface.
	 * <p>
	 * Calling this function enables event-based serial port callbacks to be used instead of direct serial port read/write calls or the {@link java.io.InputStream}/{@link java.io.OutputStream} interface.
	 * <p>
	 * The parameter passed into this method must be an implementation of either {@link SerialPortDataListener}, {@link SerialPortDataListenerWithExceptions},
	 * {@link SerialPortPacketListener}, {@link SerialPortMessageListener} or {@link SerialPortMessageListenerWithExceptions}.
	 * The {@link SerialPortMessageListener} or {@link SerialPortMessageListenerWithExceptions} interface <b>should</b> be used if you plan to use event-based reading of <i>delimited</i> data messages over the serial port.
	 * The {@link SerialPortPacketListener} interface <b>should</b> be used if you plan to use event-based reading of <i>full</i> data packets over the serial port.
	 * Otherwise, the simpler {@link SerialPortDataListener} or {@link SerialPortDataListenerWithExceptions} may be used.
	 * <p>
	 * Only one listener can be registered at a time; however, that listener can be used to detect multiple types of serial port events.
	 * Refer to {@link SerialPortDataListener}, {@link SerialPortDataListenerWithExceptions}, {@link SerialPortPacketListener}, {@link SerialPortMessageListener}, and {@link SerialPortMessageListenerWithExceptions} for more information.
	 *
	 * @param listener A {@link SerialPortDataListener}, {@link SerialPortDataListenerWithExceptions}, {@link SerialPortPacketListener}, {@link SerialPortMessageListener}, or {@link SerialPortMessageListenerWithExceptions} implementation to be used for event-based serial port communications.
	 * @return Whether the listener was successfully registered with the serial port.
	 * @see SerialPortDataListener
	 * @see SerialPortDataListenerWithExceptions
	 * @see SerialPortPacketListener
	 * @see SerialPortMessageListener
	 * @see SerialPortMessageListenerWithExceptions
	 */
	public final boolean addDataListener(SerialPortDataListener listener)
	{
		configurationLock.lock();
		try
		{
			if (userDataListener != null)
				return false;
			userDataListener = listener;
			eventFlags = listener.getListeningEvents();
			if ((eventFlags & SerialPort.LISTENING_EVENT_DATA_RECEIVED) > 0)
				eventFlags |= SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
			serialEventListener = ((userDataListener instanceof SerialPortPacketListener) ? new SerialPortEventListener(((SerialPortPacketListener)userDataListener).getPacketSize()) :
				((userDataListener instanceof SerialPortMessageListener) ?
						new SerialPortEventListener(((SerialPortMessageListener)userDataListener).getMessageDelimiter(), ((SerialPortMessageListener)userDataListener).delimiterIndicatesEndOfMessage()) :
							new SerialPortEventListener()));
			if (portHandle != 0)
			{
				if (androidPort != null)
					androidPort.configPort(this);
				else
					configPort(portHandle);
				serialEventListener.startListening();
			}
			return true;
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Flushes any already-received data from the registered {@link SerialPortDataListener} that has not yet triggered an event.
	 */
	public final void flushDataListener()
	{
		configurationLock.lock();
		try
		{
			if (serialEventListener != null)
				serialEventListener.resetBuffers();
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Removes the associated {@link SerialPortDataListener} from the serial port interface.
	 */
	public final void removeDataListener()
	{
		configurationLock.lock();
		try
		{
			eventFlags = 0;
			if (serialEventListener != null)
			{
				serialEventListener.stopListening();
				serialEventListener = null;
			}
			userDataListener = null;
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Returns an {@link java.io.InputStream} object associated with this serial port.
	 * <p>
	 * Allows for easier read access of the underlying data stream and abstracts away many low-level read details.
	 * <p>
	 * Note that any time a method is marked as throwable for an {@link java.io.IOException} in the official Java 
	 * {@link java.io.InputStream} documentation, you can catch this exception directly, or you can choose to catch
	 * either a {@link SerialPortIOException} or a {@link SerialPortTimeoutException} (or both) which may make it
	 * easier for your code to determine why the exception was thrown. In general, a {@link SerialPortIOException}
	 * means that the port is having connectivity issues, while a {@link SerialPortTimeoutException} indicates that
	 * a user timeout has been reached before valid data was able to be returned (as specified in the
	 * {@link #setComPortTimeouts(int, int, int)} method).
	 * <p>
	 * Make sure to call the {@link java.io.InputStream#close()} method when you are done using this stream.
	 *
	 * @return An {@link java.io.InputStream} object associated with this serial port.
	 * @see java.io.InputStream
	 */
	public final InputStream getInputStream() { return new SerialPortInputStream(false); }

	/**
	 * Returns an {@link java.io.InputStream} object associated with this serial port, with read timeout exceptions
	 * completely suppressed.
	 * <p>
	 * Allows for easier read access of the underlying data stream and abstracts away many low-level read details.
	 * <p>
	 * Note that any time a method is marked as throwable for an {@link java.io.IOException} in the official Java
	 * {@link java.io.InputStream} documentation, you can catch this exception directly, or you can choose to catch
	 * either a {@link SerialPortIOException} or a {@link SerialPortTimeoutException} (or both) which may make it
	 * easier for your code to determine why the exception was thrown. In general, a {@link SerialPortIOException}
	 * means that the port is having connectivity issues, while a {@link SerialPortTimeoutException} indicates that
	 * a user timeout has been reached before valid data was able to be returned (as specified in the
	 * {@link #setComPortTimeouts(int, int, int)} method). When an {@link java.io.InputStream} is returned using
	 * this method, a {@link SerialPortTimeoutException} will never be thrown.
	 * <p>
	 * Make sure to call the {@link java.io.InputStream#close()} method when you are done using this stream.
	 *
	 * @return An {@link java.io.InputStream} object associated with this serial port.
	 * @see java.io.InputStream
	 */
	public final InputStream getInputStreamWithSuppressedTimeoutExceptions() { return new SerialPortInputStream(true); }

	/**
	 * Returns an {@link java.io.OutputStream} object associated with this serial port.
	 * <p>
	 * Allows for easier write access to the underlying data stream and abstracts away many low-level writing details.
	 * <p>
	 * Note that any time a method is marked as throwable for an {@link java.io.IOException} in the official Java
	 * {@link java.io.InputStream} documentation, you can catch this exception directly, or you can choose to catch
	 * either a {@link SerialPortIOException} or a {@link SerialPortTimeoutException} (or both) which may make it
	 * easier for your code to determine why the exception was thrown. In general, a {@link SerialPortIOException}
	 * means that the port is having connectivity issues, while a {@link SerialPortTimeoutException} indicates that
	 * a user timeout has been reached before valid data was able to be returned (as specified in the
	 * {@link #setComPortTimeouts(int, int, int)} method).
	 * <p>
	 * Make sure to call the {@link java.io.OutputStream#close()} method when you are done using this stream.
	 *
	 * @return An {@link java.io.OutputStream} object associated with this serial port.
	 * @see java.io.OutputStream
	 */
	public final OutputStream getOutputStream() { return new SerialPortOutputStream(); }

	/**
	 * Flushes the serial port's Rx/Tx device buffers.
	 * <p>
	 * If this function is called before the port is open, then the buffers will be flushed immediately after opening the port.
	 * If called on an already-open port, flushing of the buffers will happen immediately.
	 * <p>
	 * Note that flushing serial buffers will always work on real serial ports; however, many USB-to-serial converters
	 * do not accurately implement this functionality, so the flushing may not always work as expected, especially immediately
	 * after opening a new port.
	 * 
	 * @return Whether the IO buffers were (or will be) successfully flushed.
	 */
	public final boolean flushIOBuffers()
	{
		configurationLock.lock();
		try
		{
			autoFlushIOBuffers = true;
			if (portHandle != 0)
				return (androidPort != null) ? androidPort.flushRxTxBuffers() : flushRxTxBuffers(portHandle);
			return true;
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Sets all serial port parameters at one time.
	 * <p>
	 * Allows the user to set all port parameters with a single function call.
	 * <p>
	 * The baud rate can be any arbitrary value specified by the user.  The default value is 9600 baud.  The data bits parameter
	 * specifies how many data bits to use per word.  The default is 8, but any values from 5 to 8 are acceptable.
	 * <p>
	 * The default number of stop bits is 1, but 2 bits can also be used or even 1.5 on Windows machines.  Please use the built-in
	 * constants for this parameter ({@link SerialPort#ONE_STOP_BIT}, {@link SerialPort#ONE_POINT_FIVE_STOP_BITS},
	 * {@link SerialPort#TWO_STOP_BITS}).
	 * <p>
	 * The parity parameter specifies how error detection is carried out.  Again, the built-in constants should be used.
	 * Acceptable values are {@link SerialPort#NO_PARITY}, {@link SerialPort#EVEN_PARITY},
	 * {@link SerialPort#ODD_PARITY}, {@link SerialPort#MARK_PARITY}, and {@link SerialPort#SPACE_PARITY}.
	 *
	 * @param newBaudRate The desired baud rate for this serial port.
	 * @param newDataBits The number of data bits to use per word.
	 * @param newStopBits The number of stop bits to use.
	 * @param newParity The type of parity error-checking desired.
	 * @return Whether the port configuration is valid or disallowed on this system (only meaningful after the port is already opened).
	 * @see SerialPort#ONE_STOP_BIT
	 * @see SerialPort#ONE_POINT_FIVE_STOP_BITS
	 * @see SerialPort#TWO_STOP_BITS
	 * @see SerialPort#NO_PARITY
	 * @see SerialPort#EVEN_PARITY
	 * @see SerialPort#ODD_PARITY
	 * @see SerialPort#MARK_PARITY
	 * @see SerialPort#SPACE_PARITY
	 */
	public final boolean setComPortParameters(int newBaudRate, int newDataBits, int newStopBits, int newParity)
	{
		return setComPortParameters(newBaudRate, newDataBits, newStopBits, newParity, rs485Mode);
	}

	/**
	 * Sets all serial port parameters at one time.
	 * <p>
	 * Allows the user to set all port parameters with a single function call.
	 * <p>
	 * The baud rate can be any arbitrary value specified by the user.  The default value is 9600 baud.  The data bits parameter
	 * specifies how many data bits to use per word.  The default is 8, but any values from 5 to 8 are acceptable.
	 * <p>
	 * The default number of stop bits is 1, but 2 bits can also be used or even 1.5 on Windows machines.  Please use the built-in
	 * constants for this parameter ({@link SerialPort#ONE_STOP_BIT}, {@link SerialPort#ONE_POINT_FIVE_STOP_BITS},
	 * {@link SerialPort#TWO_STOP_BITS}).
	 * <p>
	 * The parity parameter specifies how error detection is carried out.  Again, the built-in constants should be used.
	 * Acceptable values are {@link SerialPort#NO_PARITY}, {@link SerialPort#EVEN_PARITY},
	 * {@link SerialPort#ODD_PARITY}, {@link SerialPort#MARK_PARITY}, and {@link SerialPort#SPACE_PARITY}.
	 * <p>
	 * RS-485 mode can be used to enable transmit/receive mode signaling using the RTS pin.  This mode should be set if you plan
	 * to use this library with an RS-485 device.  Note that this mode requires support from the underlying device driver, so it
	 * may not work with all RS-485 devices.
	 *
	 * @param newBaudRate The desired baud rate for this serial port.
	 * @param newDataBits The number of data bits to use per word.
	 * @param newStopBits The number of stop bits to use.
	 * @param newParity The type of parity error-checking desired.
	 * @param useRS485Mode Whether to enable RS-485 mode.
	 * @return Whether the port configuration is valid or disallowed on this system (only meaningful after the port is already opened).
	 * @see SerialPort#ONE_STOP_BIT
	 * @see SerialPort#ONE_POINT_FIVE_STOP_BITS
	 * @see SerialPort#TWO_STOP_BITS
	 * @see SerialPort#NO_PARITY
	 * @see SerialPort#EVEN_PARITY
	 * @see SerialPort#ODD_PARITY
	 * @see SerialPort#MARK_PARITY
	 * @see SerialPort#SPACE_PARITY
	 */
	public final boolean setComPortParameters(int newBaudRate, int newDataBits, int newStopBits, int newParity, boolean useRS485Mode)
	{
		configurationLock.lock();
		try
		{
			baudRate = newBaudRate;
			dataBits = newDataBits;
			stopBits = newStopBits;
			parity = newParity;
			rs485Mode = useRS485Mode;

			if (portHandle != 0)
			{
				if (safetySleepTimeMS > 0)
					try { Thread.sleep(safetySleepTimeMS); } catch (Exception e) { Thread.currentThread().interrupt(); }
				return (androidPort != null) ? androidPort.configPort(this) : configPort(portHandle);
			}
			return true;
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Sets the serial port read and write timeout parameters.
	 * <p>
	 * <i>Note that time-based write timeouts are only available on Windows systems.
	 * There is no functionality to set a write timeout on other operating systems.</i>
	 * <p>
	 * The built-in timeout mode constants should be used ({@link SerialPort#TIMEOUT_NONBLOCKING},
	 * {@link SerialPort#TIMEOUT_READ_SEMI_BLOCKING}, {@link SerialPort#TIMEOUT_READ_BLOCKING},
	 * {@link SerialPort#TIMEOUT_WRITE_BLOCKING}, {@link SerialPort#TIMEOUT_SCANNER})
	 * to specify how timeouts are to be handled.
	 * <p>
	 * Valid modes are:
	 * <p>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Non-blocking: {@link SerialPort#TIMEOUT_NONBLOCKING}<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Write Blocking: {@link SerialPort#TIMEOUT_WRITE_BLOCKING}<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Read Semi-blocking: {@link SerialPort#TIMEOUT_READ_SEMI_BLOCKING}<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Read Full-blocking: {@link SerialPort#TIMEOUT_READ_BLOCKING}<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Scanner: {@link SerialPort#TIMEOUT_SCANNER}<br>
	 * <p>
	 * The {@link SerialPort#TIMEOUT_NONBLOCKING} mode specifies that corresponding {@link #readBytes(byte[],int)}
	 * and {@link #writeBytes(byte[],int)} calls will return immediately with any available data.
	 * <p>
	 * The {@link SerialPort#TIMEOUT_WRITE_BLOCKING} mode specifies that a corresponding write call will block until
	 * all data bytes have been successfully written to the output serial device.
	 * <p>
	 * The {@link SerialPort#TIMEOUT_READ_SEMI_BLOCKING} mode specifies that a corresponding read call will block until
	 * either <i>newReadTimeout</i> milliseconds of inactivity have elapsed or at least 1 byte of data can be read.
	 * <p>
	 * The {@link SerialPort#TIMEOUT_READ_BLOCKING} mode specifies that a corresponding read call will block until either
	 * <i>newReadTimeout</i> milliseconds have elapsed since the start of the call or the total number of requested bytes can be returned.
	 * <p>
	 * The {@link SerialPort#TIMEOUT_SCANNER} mode is intended for use with the Java {@link java.util.Scanner} class for
	 * reading from the serial port. In this mode, manually specified timeouts are ignored to ensure compatibility with the Java specification.
	 * <p>
	 * A value of 0 for either <i>newReadTimeout</i> or <i>newWriteTimeout</i> indicates that a {@link #readBytes(byte[],int)} or
	 * {@link #writeBytes(byte[],int)} call should block forever until it can return successfully (based upon the current timeout mode specified).
	 * <p>
	 * In order to specify that both a blocking read and write mode should be used, {@link SerialPort#TIMEOUT_WRITE_BLOCKING}
	 * can be OR'd together with any of the read modes to pass to the first parameter.
	 * <p>
	 * It is important to note that non-Windows operating systems only allow decisecond (1/10th of a second) granularity for serial port timeouts.
	 * As such, your millisecond timeout value will be rounded to the nearest decisecond under Linux or Mac OS. To ensure consistent performance
	 * across multiple platforms, it is advisable that you set your timeout values to be multiples of 100, although this is not strictly enforced.
	 * <p>
	 * Also note that if the serial port has an event-based data listener actively registered for the event type
	 * {@link SerialPort#LISTENING_EVENT_DATA_RECEIVED}, all serial port timeout settings are ignored.
	 *
	 * @param newTimeoutMode The new timeout mode as specified above.
	 * @param newReadTimeout The number of milliseconds of inactivity to tolerate before returning from a {@link #readBytes(byte[],int)} call.
	 * @param newWriteTimeout The number of milliseconds of inactivity to tolerate before returning from a {@link #writeBytes(byte[],int)} call (effective only on Windows).
	 * @return Whether the port configuration is valid or disallowed on this system (only meaningful after the port is already opened).
	 */
	public final boolean setComPortTimeouts(int newTimeoutMode, int newReadTimeout, int newWriteTimeout)
	{
		configurationLock.lock();
		try
		{
			timeoutMode = newTimeoutMode;
			if (isWindows)
			{
				readTimeout = newReadTimeout;
				writeTimeout = newWriteTimeout;
			}
			else if ((newReadTimeout > 0) && (newReadTimeout <= 100))
				readTimeout = 100;
			else
				readTimeout = Math.round((float)newReadTimeout / 100.0f) * 100;

			if (portHandle != 0)
			{
				if (safetySleepTimeMS > 0)
					try { Thread.sleep(safetySleepTimeMS); } catch (Exception e) { Thread.currentThread().interrupt(); }
				return (androidPort != null) ? androidPort.configPort(this) : configPort(portHandle);
			}
			return true;
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Sets the desired baud rate for this serial port.
	 * <p>
	 * The default baud rate is 9600 baud.
	 * <p>
	 * Note: Using baud rates above 230400 on <b>Apple</b> computers requires additional
	 * steps to enable their use that are beyond the scope of this Javadoc.
	 * <p>
	 * Note: For <b>USB serial adapters</b> (like the SiLabs CP210x family, commonly used with Arduino and ESP32 boards),
	 * the baud rate set by a call to {@link #setBaudRate(int)} dictates the baud rate on the TxD and RxD pins at the
	 * <i>other end</i> of the USB port, and generally has <i>no effect</i> on the mode or transfer rate of bits across the USB bus itself.
	 * <ul>
	 *   <li>The actual <i>achievable</i> transfer rate MIGHT be limited by the USB transfer mode used by the kernel driver itself.</li>
	 *   <li>Many (though not necessarily ALL) USB-serial adapters use USB "control" transfers. Control transfers deliver a 64-byte payload
	 *       in each direction at 1 millisecond intervals. This implicitly imposes an absolute speed limit of 64 x 8 x 1000 = 512kbps,
	 *       minus any overhead related to communicating the state of DTR, RTS, etc. Your interface might be different, but keep this in
	 *       mind as a possibility if you notice that baudrates above 460kbps (or even 230kbps) appear to be dropping bytes.</li>
	 *   <li>This doesn't mean you can't set a baudrate of "1mbps" (or 921kbps, or some other baudrate supported at the other end of the
	 *       serial adapter), but it DOES mean you'll probably have to implement some form of flow control to avoid overflowing the USB-serial
	 *       adapter's send buffer if you're filling it with bytes faster than the host PC is polling for them.</li>
	 *   <li>Also, be aware that things like start/stop bits and parity are handled at the USB adapter's end. A byte might require 10
	 *       clock cycles at your baudrate to transfer a start bit, 8 data bits, and a stop bit between the USB adapter's TxD/RxD pins, and
	 *       whatever you connect to them, but the actual payload delivered across the USB bus will consume only 8 bits per byte.</li>
	 * </ul>
	 *
	 * @param newBaudRate The desired baud rate for this serial port.
	 * @return Whether the port configuration is valid or disallowed on this system (only meaningful after the port is already opened).
	 */
	public final boolean setBaudRate(int newBaudRate)
	{
		configurationLock.lock();
		try
		{
			baudRate = newBaudRate;
			if (portHandle != 0)
			{
				if (safetySleepTimeMS > 0)
					try { Thread.sleep(safetySleepTimeMS); } catch (Exception e) { Thread.currentThread().interrupt(); }
				return (androidPort != null) ? androidPort.configPort(this) : configPort(portHandle);
			}
			return true;
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Sets the desired number of data bits per word.
	 * <p>
	 * The default number of data bits per word is 8.
	 *
	 * @param newDataBits The desired number of data bits per word.
	 * @return Whether the port configuration is valid or disallowed on this system (only meaningful after the port is already opened).
	 */
	public final boolean setNumDataBits(int newDataBits)
	{
		configurationLock.lock();
		try
		{
			dataBits = newDataBits;
			if (portHandle != 0)
			{
				if (safetySleepTimeMS > 0)
					try { Thread.sleep(safetySleepTimeMS); } catch (Exception e) { Thread.currentThread().interrupt(); }
				return (androidPort != null) ? androidPort.configPort(this) : configPort(portHandle);
			}
			return true;
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Sets the desired number of stop bits per word.
	 * <p>
	 * The default number of stop bits per word is 1.  Built-in stop-bit constants should be used
	 * in this method ({@link SerialPort#ONE_STOP_BIT}, {@link SerialPort#ONE_POINT_FIVE_STOP_BITS},
	 * {@link SerialPort#TWO_STOP_BITS}).
	 * <p>
	 * Note that {@link SerialPort#ONE_POINT_FIVE_STOP_BITS} stop bits may not be available on non-Windows systems.
	 *
	 * @param newStopBits The desired number of stop bits per word.
	 * @return Whether the port configuration is valid or disallowed on this system (only meaningful after the port is already opened).
	 * @see SerialPort#ONE_STOP_BIT
	 * @see SerialPort#ONE_POINT_FIVE_STOP_BITS
	 * @see SerialPort#TWO_STOP_BITS
	 */
	public final boolean setNumStopBits(int newStopBits)
	{
		configurationLock.lock();
		try
		{
			stopBits = newStopBits;
			if (portHandle != 0)
			{
				if (safetySleepTimeMS > 0)
					try { Thread.sleep(safetySleepTimeMS); } catch (Exception e) { Thread.currentThread().interrupt(); }
				return (androidPort != null) ? androidPort.configPort(this) : configPort(portHandle);
			}
			return true;
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Specifies what kind of flow control to enable for this serial port.
	 * <p>
	 * By default, no flow control is enabled.  Built-in flow control constants should be used
	 * in this method ({@link SerialPort#FLOW_CONTROL_RTS_ENABLED}, {@link SerialPort#FLOW_CONTROL_CTS_ENABLED},
	 * {@link SerialPort#FLOW_CONTROL_DTR_ENABLED}, {@link SerialPort#FLOW_CONTROL_DSR_ENABLED},
	 * {@link SerialPort#FLOW_CONTROL_XONXOFF_IN_ENABLED}, {@link SerialPort#FLOW_CONTROL_XONXOFF_OUT_ENABLED}),
	 * and can be OR'ed together.
	 * <p>
	 * Valid flow control configurations are:
	 * <p>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;None: {@link SerialPort#FLOW_CONTROL_DISABLED}<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;CTS: {@link SerialPort#FLOW_CONTROL_CTS_ENABLED}<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;RTS/CTS: {@link SerialPort#FLOW_CONTROL_RTS_ENABLED} | {@link SerialPort#FLOW_CONTROL_CTS_ENABLED}<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;DSR: {@link SerialPort#FLOW_CONTROL_DSR_ENABLED}<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;DTR/DSR: {@link SerialPort#FLOW_CONTROL_DTR_ENABLED} | {@link SerialPort#FLOW_CONTROL_DSR_ENABLED}<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;XOn/XOff: {@link SerialPort#FLOW_CONTROL_XONXOFF_IN_ENABLED} | {@link SerialPort#FLOW_CONTROL_XONXOFF_OUT_ENABLED}
	 * <p>
	 * Note that only one valid flow control configuration can be used at any time.  For example, attempting to use both XOn/XOff
	 * <b>and</b> RTS/CTS will most likely result in an unusable serial port.
	 * <p>
	 * Also note that some flow control modes are only available on certain operating systems. Valid modes for each OS are:
	 * <p>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Windows: CTS, RTS/CTS, DSR, DTR/DSR, Xon, Xoff, Xon/Xoff<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Mac: RTS/CTS, Xon, Xoff, Xon/Xoff<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Linux: RTS/CTS, Xon, Xoff, Xon/Xoff
	 *
	 * @param newFlowControlSettings The desired type of flow control to enable for this serial port.
	 * @return Whether the port configuration is valid or disallowed on this system (only meaningful after the port is already opened).
	 * @see SerialPort#FLOW_CONTROL_DISABLED
	 * @see SerialPort#FLOW_CONTROL_RTS_ENABLED
	 * @see SerialPort#FLOW_CONTROL_CTS_ENABLED
	 * @see SerialPort#FLOW_CONTROL_DTR_ENABLED
	 * @see SerialPort#FLOW_CONTROL_DSR_ENABLED
	 * @see SerialPort#FLOW_CONTROL_XONXOFF_IN_ENABLED
	 * @see SerialPort#FLOW_CONTROL_XONXOFF_OUT_ENABLED
	 */
	public final boolean setFlowControl(int newFlowControlSettings)
	{
		configurationLock.lock();
		try
		{
			flowControl = newFlowControlSettings;
			if (portHandle != 0)
			{
				if (safetySleepTimeMS > 0)
					try { Thread.sleep(safetySleepTimeMS); } catch (Exception e) { Thread.currentThread().interrupt(); }
				return (androidPort != null) ? androidPort.configPort(this) : configPort(portHandle);
			}
			return true;
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Sets the desired parity error-detection scheme to be used.
	 * <p>
	 * The parity parameter specifies how error detection is carried out.  The built-in parity constants should be used.
	 * Acceptable values are {@link SerialPort#NO_PARITY}, {@link SerialPort#EVEN_PARITY},
	 * {@link SerialPort#ODD_PARITY}, {@link SerialPort#MARK_PARITY}, and {@link SerialPort#SPACE_PARITY}.
	 *
	 * @param newParity The desired parity scheme to be used.
	 * @return Whether the port configuration is valid or disallowed on this system (only meaningful after the port is already opened).
	 * @see SerialPort#NO_PARITY
	 * @see SerialPort#EVEN_PARITY
	 * @see SerialPort#ODD_PARITY
	 * @see SerialPort#MARK_PARITY
	 * @see SerialPort#SPACE_PARITY
	 */
	public final boolean setParity(int newParity)
	{
		configurationLock.lock();
		try
		{
			parity = newParity;
			if (portHandle != 0)
			{
				if (safetySleepTimeMS > 0)
					try { Thread.sleep(safetySleepTimeMS); } catch (Exception e) { Thread.currentThread().interrupt(); }
				return (androidPort != null) ? androidPort.configPort(this) : configPort(portHandle);
			}
			return true;
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Sets RS-485 mode and its parameters for the device. This is a wrapper around
	 * {@link #setRs485ModeParameters(boolean, boolean, boolean, boolean, int, int)} for backward-compatibility which disables bus termination and
	 * receiving data while transmitting.
	 *
	 * @see #setRs485ModeParameters(boolean, boolean, boolean, boolean, int, int)
	 *
	 * @param useRS485Mode Whether to enable RS-485 mode.
	 * @param rs485RtsActiveHigh Whether to set the RTS line to 1 when transmitting (effective only on Linux).
	 * @param delayBeforeSendMicroseconds The time to wait after enabling transmit mode before sending the first data bit (effective only on Linux).
	 * @param delayAfterSendMicroseconds The time to wait after sending the last data bit before disabling transmit mode (effective only on Linux).
	 * @return Whether the port configuration is valid or disallowed on this system (only meaningful after the port is already opened).
	 */
	public final boolean setRs485ModeParameters(boolean useRS485Mode, boolean rs485RtsActiveHigh, int delayBeforeSendMicroseconds, int delayAfterSendMicroseconds)
	{
		return setRs485ModeParameters(useRS485Mode, rs485RtsActiveHigh, false, false, delayBeforeSendMicroseconds, delayAfterSendMicroseconds);
	}

	/**
	 * Sets RS-485 mode and its parameters for the device.
	 * <p>
	 * RS-485 mode can be used to enable transmit/receive mode signaling using the RTS pin. This mode should be set if you plan
	 * to use this library with an RS-485 device. Note that this mode requires support from the underlying device driver, so it
	 * may not work with all RS-485 devices. On the other hand there are devices that operate in RS-485 mode by
	 * default and do not require explicit configuration (like some USB to RS-485 adapters).
	 * <p>
	 * Please note that the parameters beyond <i>useRS485Mode</i> are only effective on Linux.
	 * <p>
	 * The RTS "active high" parameter specifies that the logical level of the RTS line will be set to 1 when transmitting and
	 * 0 when receiving.
	 * <p>
	 * The delay parameters specify how long to wait before or after transmission of data after enabling or before disabling
	 * transmission mode via the RTS pin.
	 *
	 * @param useRS485Mode Whether to enable RS-485 mode.
	 * @param rs485RtsActiveHigh Whether to set the RTS line to 1 when transmitting (effective only on Linux).
	 * @param enableTermination Whether to enable RS-485 bus termination on systems supporting this feature (effective only on Linux).
	 * @param rxDuringTx Whether to receive data while transmitting. This usually means that data sent will be read back (effective only on Linux).
	 * @param delayBeforeSendMicroseconds The time to wait after enabling transmit mode before sending the first data bit (effective only on Linux).
	 * @param delayAfterSendMicroseconds The time to wait after sending the last data bit before disabling transmit mode (effective only on Linux).
	 * @return Whether the port configuration is valid or disallowed on this system (only meaningful after the port is already opened).
	 */
	public final boolean setRs485ModeParameters(boolean useRS485Mode, boolean rs485RtsActiveHigh, boolean enableTermination, boolean rxDuringTx,int delayBeforeSendMicroseconds, int delayAfterSendMicroseconds)
	{
		configurationLock.lock();
		try
		{
			rs485Mode = useRS485Mode;
			rs485ActiveHigh = rs485RtsActiveHigh;
			rs485EnableTermination = enableTermination;
			rs485RxDuringTx = rxDuringTx;
			rs485DelayBefore = delayBeforeSendMicroseconds;
			rs485DelayAfter = delayAfterSendMicroseconds;

			if (portHandle != 0)
			{
				if (safetySleepTimeMS > 0)
					try { Thread.sleep(safetySleepTimeMS); } catch (Exception e) { Thread.currentThread().interrupt(); }
				return (androidPort != null) ? androidPort.configPort(this) : configPort(portHandle);
			}
			return true;
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Sets custom XON/XOFF flow control characters for the device.
	 * <p>
	 * Custom characters should almost never be used, as most devices expect the XON/START character to be 17 and the
	 * XOFF/STOP character to be 13. If your device expects different flow control characters, they may be changed using
	 * this function.
	 *
	 * @param xonStartCharacter The decimal-based character to use as an XON signal.
	 * @param xoffStopCharacter The decimal-based character to use as an XOFF signal.
	 * @return Whether the port configuration is valid or disallowed on this system (only meaningful after the port is already opened).
	 */
	public final boolean setXonXoffCharacters(byte xonStartCharacter, byte xoffStopCharacter)
	{
		configurationLock.lock();
		try
		{
			xonStartChar = xonStartCharacter;
			xoffStopChar = xoffStopCharacter;
			if (portHandle != 0)
			{
				if (safetySleepTimeMS > 0)
					try { Thread.sleep(safetySleepTimeMS); } catch (Exception e) { Thread.currentThread().interrupt(); }
				return (androidPort != null) ? androidPort.configPort(this) : configPort(portHandle);
			}
			return true;
		}
		finally { configurationLock.unlock(); }
	}

	/**
	 * Gets a descriptive string representing this serial port or the device connected to it.
	 * <p>
	 * This description is generated by the operating system and may or may not be either unique or 
	 * a good representation of the actual port or device it describes.
	 *
	 * @return A descriptive string representing this serial port.
	 */
	public final String getDescriptivePortName() { return friendlyName.trim(); }

	/**
	 * Gets the operating system-defined device name corresponding to this serial port. The name will be unique,
	 * returning a different string for multiples of the same type of device connected to a single computer.
	 *
	 * @return The system-defined device name of this serial port.
	 */
	public final String getSystemPortName() { return (isWindows ? comPort.substring(comPort.lastIndexOf('\\')+1) : comPort.substring(comPort.lastIndexOf('/')+1)); }

	/**
	 * Gets the operating system-defined device path corresponding to this serial port. The path will be unique,
	 * returning a different string for multiples of the same type of device connected to a single computer.
	 *
	 * @return The system-defined device path of this serial port.
	 */
	public final String getSystemPortPath() { return comPort; }

	/**
	 * Gets a description of the port as reported by the device itself.
	 * <p>
	 * This will only be available for USB-connected devices that report a product description.
	 * Otherwise, it will return the same value as {@link #getDescriptivePortName()}.
	 *
	 * @return The port description as reported by the device itself.
	 */
	public final String getPortDescription() { return portDescription.trim(); }

	/**
	 * Gets the physical location of the port as a String in the form "BUS-[HUB1.HUB2.etc]PORT_NUMBER".
	 * <p>
	 * "[HUB1.HUB2...]" is an optional field that refers to the hierarchy of USB hub numbers that a device
	 * might be plugged into. For example, a USB-to-Serial Converter plugged into the third port of a USB hub
	 * which is plugged into another USB hub which is plugged into a USB bus on a PC might have the port
	 * location "1-1.1.3". A device plugged directly into a PC-based serial or USB port might have a port
	 * location of "1-2". A virtual (non-physical) serial port might return a value of "0-0" since this
	 * port has no physical location.
	 * <p>
	 * This method may be used to uniquely identify a device in the case where multiples of the same type
	 * of device are present on the same system. In this case, the operating system might assign each device
	 * to a different port number upon reboot; however, the port locations will remain the same as long
	 * as each device remains physically plugged into the same port.
	 * <p>
	 * Note, if you manually specified the port location using {@link #getCommPort}, this method will
	 * always return "0-0".
	 *
	 * @return The physical port location in the form "BUS-[HUB1.HUB2.etc]PORT_NUMBER".
	 */
	public final String getPortLocation() { return portLocation; }

	/**
	 * Gets the 16-bit Vendor Identification number for the serial port, assuming it is USB-based.
	 *
	 * @return The VID of this serial port, or -1 if it is not USB-based.
	 */
	public final int getVendorID() { return vendorID; }

	/**
	 * Gets the 16-bit Product Identification number for the serial port, assuming it is USB-based.
	 *
	 * @return The PID of this serial port, or -1 if it is not USB-based.
	 */
	public final int getProductID() { return productID; }

	/**
	 * Gets the Serial Number for the serial port, assuming it is USB-based.
	 * 
	 * @return The Serial Number of this serial port, or "Unknown" if it is not USB-based.
	 */
	public final String getSerialNumber() { return serialNumber; }

	/**
	 * Gets the Manufacturer for the serial port, assuming it is USB-based.
	 * 
	 * @return The Manufacturer of this serial port, or "Unknown" if it is not USB-based.
	 */
	public final String getManufacturer() { return manufacturer; }

	/**
	 * Gets the current baud rate of the serial port.
	 *
	 * @return The current baud rate of the serial port.
	 */
	public final int getBaudRate() { return baudRate; }

	/**
	 * Gets the current number of data bits per word.
	 *
	 * @return The current number of data bits per word.
	 */
	public final int getNumDataBits() { return dataBits; }

	/**
	 * Gets the current number of stop bits per word.
	 * <p>
	 * The return value should not be interpreted as an integer, but rather compared with the built-in stop bit constants
	 * ({@link SerialPort#ONE_STOP_BIT}, {@link SerialPort#ONE_POINT_FIVE_STOP_BITS},
	 * {@link SerialPort#TWO_STOP_BITS}).
	 *
	 * @return The current number of stop bits per word.
	 * @see SerialPort#ONE_STOP_BIT
	 * @see SerialPort#ONE_POINT_FIVE_STOP_BITS
	 * @see SerialPort#TWO_STOP_BITS
	 */
	public final int getNumStopBits() { return stopBits; }

	/**
	 * Gets the current parity error-checking scheme.
	 * <p>
	 * The return value should not be interpreted as an integer, but rather compared with the built-in parity constants
	 * ({@link SerialPort#NO_PARITY}, {@link SerialPort#EVEN_PARITY}, {@link SerialPort#ODD_PARITY},
	 * {@link SerialPort#MARK_PARITY}, and {@link SerialPort#SPACE_PARITY}).
	 *
	 * @return The current parity scheme.
	 * @see SerialPort#NO_PARITY
	 * @see SerialPort#EVEN_PARITY
	 * @see SerialPort#ODD_PARITY
	 * @see SerialPort#MARK_PARITY
	 * @see SerialPort#SPACE_PARITY
	 */
	public final int getParity() { return parity; }

	/**
	 * Gets the number of milliseconds of inactivity to tolerate before returning from a {@link #readBytes(byte[],int)} call.
	 * <p>
	 * A value of 0 in blocking-read mode indicates that a {@link #readBytes(byte[],int)} call will block forever until it has successfully read
	 * the indicated number of bytes from the serial port.
	 * <p>
	 * Any value other than 0 indicates the number of milliseconds of inactivity that will be tolerated before the {@link #readBytes(byte[],int)}
	 * call will return.
	 *
	 * @return The number of milliseconds of inactivity to tolerate before returning from a {@link #readBytes(byte[],int)} call.
	 */
	public final int getReadTimeout() { return readTimeout; }

	/**
	 * Gets the number of milliseconds of inactivity to tolerate before returning from a {@link #writeBytes(byte[],int)} call.
	 * <p>
	 * A value of 0 in blocking-write mode indicates that a {@link #writeBytes(byte[],int)} call will block forever until it has successfully written
	 * the indicated number of bytes to the serial port.
	 * <p>
	 * Any value other than 0 indicates the number of milliseconds of inactivity that will be tolerated before the {@link #writeBytes(byte[],int)}
	 * call will return.
	 * <p>
	 * Note that write timeouts are only available on Windows operating systems. This value is ignored on all other systems.
	 *
	 * @return The number of milliseconds of inactivity to tolerate before returning from a {@link #writeBytes(byte[],int)} call.
	 */
	public final int getWriteTimeout() { return writeTimeout; }

	/**
	 * Returns the flow control settings enabled on this serial port.
	 * <p>
	 * The integer result should be masked with the built-in flow control constants to test if the desired setting is enabled.
	 * Valid flow control configurations are:
	 * <p>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;None: {@link SerialPort#FLOW_CONTROL_DISABLED}<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;CTS: {@link SerialPort#FLOW_CONTROL_CTS_ENABLED}<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;RTS/CTS: {@link SerialPort#FLOW_CONTROL_RTS_ENABLED} | {@link SerialPort#FLOW_CONTROL_CTS_ENABLED}<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;DSR: {@link SerialPort#FLOW_CONTROL_DSR_ENABLED}<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;DTR/DSR: {@link SerialPort#FLOW_CONTROL_DTR_ENABLED} | {@link SerialPort#FLOW_CONTROL_DSR_ENABLED}<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;XOn/XOff: {@link SerialPort#FLOW_CONTROL_XONXOFF_IN_ENABLED} | {@link SerialPort#FLOW_CONTROL_XONXOFF_OUT_ENABLED}
	 * <p>
	 * Note that some flow control modes are only available on certain operating systems. Valid modes for each OS are:
	 * <p>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Windows: CTS, RTS/CTS, DSR, DTR/DSR, Xon, Xoff, Xon/Xoff<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Mac: RTS/CTS, Xon, Xoff, Xon/Xoff<br>
	 * &nbsp;&nbsp;&nbsp;&nbsp;&nbsp;Linux: RTS/CTS, Xon, Xoff, Xon/Xoff
	 *
	 * @return The flow control settings enabled on this serial port.
	 * @see SerialPort#FLOW_CONTROL_DISABLED
	 * @see SerialPort#FLOW_CONTROL_RTS_ENABLED
	 * @see SerialPort#FLOW_CONTROL_CTS_ENABLED
	 * @see SerialPort#FLOW_CONTROL_DTR_ENABLED
	 * @see SerialPort#FLOW_CONTROL_DSR_ENABLED
	 * @see SerialPort#FLOW_CONTROL_XONXOFF_IN_ENABLED
	 * @see SerialPort#FLOW_CONTROL_XONXOFF_OUT_ENABLED
	 */
	public final int getFlowControlSettings() { return flowControl; }

	// Private EventListener class
	private final class SerialPortEventListener
	{
		private final boolean messageEndIsDelimited;
		private final byte[] dataPacket, delimiters;
		private final ByteArrayOutputStream messageBytes = new ByteArrayOutputStream();
		private int dataPacketIndex = 0, delimiterIndex = 0;
		private Thread serialEventThread = null;

		public SerialPortEventListener() { dataPacket = new byte[0]; delimiters = new byte[0]; messageEndIsDelimited = true; }
		public SerialPortEventListener(int packetSizeToReceive) { dataPacket = new byte[packetSizeToReceive]; delimiters = new byte[0]; messageEndIsDelimited = true; }
		public SerialPortEventListener(byte[] messageDelimiters, boolean delimiterForMessageEnd) { dataPacket = new byte[0]; delimiters = messageDelimiters; messageEndIsDelimited = delimiterForMessageEnd; }

		public final void startListening()
		{
			if (eventListenerRunning)
				return;
			eventListenerRunning = true;

			// Reset event listening parameters and start a new listening thread
			resetBuffers();
			if (androidPort != null)
				androidPort.setEventListeningStatus(true);
			else
				setEventListeningStatus(portHandle, true);
			serialEventThread = SerialPortThreadFactory.get().newThread(new Runnable()
			{
				@Override
				public void run()
				{
					while (eventListenerRunning && !isShuttingDown)
					{
						try { waitForSerialEvent(); }
						catch (Exception e)
						{
							eventListenerRunning = false;
							if (userDataListener instanceof SerialPortDataListenerWithExceptions)
								((SerialPortDataListenerWithExceptions)userDataListener).catchException(e);
							else if (userDataListener instanceof SerialPortMessageListenerWithExceptions)
								((SerialPortMessageListenerWithExceptions)userDataListener).catchException(e);
						}
					}
				}
			});
			serialEventThread.start();
		}

		public final void stopListening()
		{
			if (!eventListenerRunning)
				return;
			eventListenerRunning = false;

			// Clear all timeouts and event masks to allow listening threads to return
			int oldTimeoutMode = timeoutMode, oldEventFlags = eventFlags;
			timeoutMode = TIMEOUT_NONBLOCKING;
			eventFlags = 0;
			if (androidPort != null)
			{
				androidPort.setEventListeningStatus(false);
				androidPort.configPort(SerialPort.this);
			}
			else
			{
				setEventListeningStatus(portHandle, false);
				configPort(portHandle);
			}

			// Wait until the event-reading thread returns. This thread MUST return or the serial port will
			//   be in an unspecified, possibly unrecoverable state
			try
			{
				if (!Thread.currentThread().equals(serialEventThread))
					do
					{
						serialEventThread.join(500);
						if (serialEventThread.isAlive())
							serialEventThread.interrupt();
					} while (serialEventThread.isAlive());
			}
			catch (InterruptedException e) { Thread.currentThread().interrupt(); }
			serialEventThread = null;

			// Reset the previously specified timeouts and event flags
			timeoutMode = oldTimeoutMode;
			eventFlags = oldEventFlags;
			if (androidPort != null)
				androidPort.configPort(SerialPort.this);
			else
				configPort(portHandle);
		}

		public final void resetBuffers()
		{
			messageBytes.reset();
			delimiterIndex = dataPacketIndex = 0;
		}

		public final void waitForSerialEvent() throws Exception
		{
			int event = ((androidPort != null) ? androidPort.waitForEvent() : waitForEvent(portHandle)) & eventFlags;
			if (((event & SerialPort.LISTENING_EVENT_DATA_AVAILABLE) > 0) && ((eventFlags & SerialPort.LISTENING_EVENT_DATA_RECEIVED) > 0))
			{
				// Read data from serial port
				int numBytesAvailable, bytesRemaining, newBytesIndex;
				event &= ~(SerialPort.LISTENING_EVENT_DATA_AVAILABLE | SerialPort.LISTENING_EVENT_DATA_RECEIVED);
				while (eventListenerRunning && ((numBytesAvailable = bytesAvailable()) > 0))
				{
					newBytesIndex = 0;
					byte[] newBytes = new byte[numBytesAvailable];
					bytesRemaining = readBytes(newBytes, newBytes.length);
					if (bytesRemaining > 0)
					{
						if (delimiters.length > 0)
						{
							int startIndex = 0;
							for (int offset = 0; offset < bytesRemaining; ++offset)
							{
								if (newBytes.length == 0)
								{
									break;
								}
								for (int i = 0; i < delimiters.length; i++)
								{
									delimiterIndex = i;
									byte delimiter = delimiters[i];
									if (newBytes.length >= (offset + 1) &&  newBytes[offset] == delimiter)
									{
										messageBytes.write(newBytes, startIndex, 1 + offset - startIndex);
										byte[] byteArray = (messageEndIsDelimited ? messageBytes.toByteArray() : Arrays.copyOf(messageBytes.toByteArray(), messageBytes.size() - offset));
										if ((byteArray.length > 0) && (messageEndIsDelimited || (delimiters[i] == byteArray[0])))
											userDataListener.serialEvent(new SerialPortEvent(SerialPort.this, SerialPort.LISTENING_EVENT_DATA_RECEIVED, byteArray));
										startIndex = offset + 1;
										messageBytes.reset();
										if (!messageEndIsDelimited)
											messageBytes.write(newBytes, 0, newBytes.length);

										//use -1 to exclude delimiter from the read?
										newBytes = Arrays.copyOfRange(newBytes, startIndex, newBytes.length);
										offset = 0;
										startIndex = 0;
										bytesRemaining = newBytes.length;
									}
								}
							}
							messageBytes.write(newBytes, startIndex, bytesRemaining - startIndex);
						}
						else if (dataPacket.length == 0)
							userDataListener.serialEvent(new SerialPortEvent(SerialPort.this, SerialPort.LISTENING_EVENT_DATA_RECEIVED, newBytes.clone()));
						else
						{
							while (bytesRemaining >= (dataPacket.length - dataPacketIndex))
							{
								System.arraycopy(newBytes, newBytesIndex, dataPacket, dataPacketIndex, dataPacket.length - dataPacketIndex);
								bytesRemaining -= (dataPacket.length - dataPacketIndex);
								newBytesIndex += (dataPacket.length - dataPacketIndex);
								dataPacketIndex = 0;
								userDataListener.serialEvent(new SerialPortEvent(SerialPort.this, SerialPort.LISTENING_EVENT_DATA_RECEIVED, dataPacket.clone()));
							}
							if (bytesRemaining > 0)
							{
								System.arraycopy(newBytes, newBytesIndex, dataPacket, dataPacketIndex, bytesRemaining);
								dataPacketIndex += bytesRemaining;
							}
						}
					}
				}
			}
			if (eventListenerRunning && !isShuttingDown && (event != SerialPort.LISTENING_EVENT_TIMED_OUT))
				userDataListener.serialEvent(new SerialPortEvent(SerialPort.this, event));
		}
	}

	// InputStream interface class
	private final class SerialPortInputStream extends InputStream
	{
		private final boolean timeoutExceptionsSuppressed;
		private final byte[] byteBuffer = new byte[1];

		public SerialPortInputStream(boolean suppressReadTimeoutExceptions)
		{
			timeoutExceptionsSuppressed = suppressReadTimeoutExceptions;
		}

		@Override
		public final int available() throws SerialPortIOException
		{
			if (portHandle == 0)
				throw new SerialPortIOException("This port appears to have been shutdown or disconnected.");
			return bytesAvailable();
		}

		@Override
		public final int read() throws SerialPortIOException, SerialPortTimeoutException
		{
			// Perform error checking
			if (portHandle == 0)
				throw new SerialPortIOException("This port appears to have been shutdown or disconnected.");

			// Read from the serial port
			int numRead = readBytes(byteBuffer, 1);
			if (numRead == 0)
			{
				if (timeoutExceptionsSuppressed)
					return -1;
				else
					throw new SerialPortTimeoutException("The read operation timed out before any data was returned.");
			}
			return (numRead < 0) ? -1 : ((int)byteBuffer[0] & 0xFF);
		}

		@Override
		public final int read(byte[] b) throws NullPointerException, SerialPortIOException, SerialPortTimeoutException
		{
			// Perform error checking
			if (b == null)
				throw new NullPointerException("A null pointer was passed in for the read buffer.");
			if (portHandle == 0)
				throw new SerialPortIOException("This port appears to have been shutdown or disconnected.");
			if (b.length == 0)
				return 0;

			// Read from the serial port
			int numRead = readBytes(b, b.length);
			if ((numRead == 0) && !timeoutExceptionsSuppressed)
				throw new SerialPortTimeoutException("The read operation timed out before any data was returned.");
			return numRead;
		}

		@Override
		public final int read(byte[] b, int off, int len) throws NullPointerException, IndexOutOfBoundsException, SerialPortIOException, SerialPortTimeoutException
		{
			// Perform error checking
			if (b == null)
				throw new NullPointerException("A null pointer was passed in for the read buffer.");
			if ((len < 0) || (off < 0) || (len > (b.length - off)))
				throw new IndexOutOfBoundsException("The specified read offset plus length extends past the end of the specified buffer.");
			if (portHandle == 0)
				throw new SerialPortIOException("This port appears to have been shutdown or disconnected.");
			if ((b.length == 0) || (len == 0))
				return 0;

			// Read from the serial port
			int numRead = readBytes(b, len, off);
			if ((numRead == 0) && !timeoutExceptionsSuppressed)
				throw new SerialPortTimeoutException("The read operation timed out before any data was returned.");
			return numRead;
		}

		@Override
		public final long skip(long n) throws SerialPortIOException
		{
			if (portHandle == 0)
				throw new SerialPortIOException("This port appears to have been shutdown or disconnected.");
			long bytesSkipped = 0L;
			final int bufferSize = (n > 4096L) ? 4096 : (int)n;
			byte[] buffer = new byte[bufferSize];
			for (long i = 0L, bytesRead = 1L; (bytesRead > 0L) && (i < n); i += bufferSize)
			{
				bytesRead = readBytes(buffer, (int)Math.min(n-i, bufferSize));
				if (bytesRead > 0L)
					bytesSkipped += bytesRead;
			}
			return bytesSkipped;
		}
	}

	// OutputStream interface class
	private final class SerialPortOutputStream extends OutputStream
	{
		private final byte[] byteBuffer = new byte[1];

		public SerialPortOutputStream() {}

		@Override
		public final void write(int b) throws SerialPortIOException, SerialPortTimeoutException
		{
			if (portHandle == 0)
				throw new SerialPortIOException("This port appears to have been shutdown or disconnected.");
			byteBuffer[0] = (byte)(b & 0xFF);
			int bytesWritten = writeBytes (byteBuffer, 1);
			if (bytesWritten < 0)
				throw new SerialPortIOException("No bytes written. This port appears to have been shutdown or disconnected.");
			else if (bytesWritten == 0)
				throw new SerialPortTimeoutException("The write operation timed out before all data was written.");
		}

		@Override
		public final void write(byte[] b) throws NullPointerException, SerialPortIOException, SerialPortTimeoutException
		{
			write(b, 0, b.length);
		}

		@Override
		public final void write(byte[] b, int off, int len) throws NullPointerException, IndexOutOfBoundsException, SerialPortIOException, SerialPortTimeoutException
		{
			// Perform error checking
			if (b == null)
				throw new NullPointerException("A null pointer was passed in for the write buffer.");
			if ((len < 0) || (off < 0) || ((off + len) > b.length))
				throw new IndexOutOfBoundsException("The specified write offset plus length extends past the end of the specified buffer.");

			// Write to the serial port until all bytes have been consumed
			int totalNumWritten = 0;
			while (totalNumWritten != len)
			{
				// Always ensure that the port has not been closed
				if (portHandle == 0)
					throw new SerialPortIOException("This port appears to have been shutdown or disconnected.");

				// Write the actual bytes to the serial port
				int numWritten = writeBytes(b, len - totalNumWritten, off + totalNumWritten);
				if (numWritten < 0)
					throw new SerialPortIOException("No bytes written. This port appears to have been shutdown or disconnected.");
				else if (numWritten == 0)
					throw new SerialPortTimeoutException("The write operation timed out before all data was written.");
				else
					totalNumWritten += numWritten;
			}
		}
	}
}
