package dev.dunehd.jellyfinbridge;

import android.util.Log;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

final class NfsMediaResolver {
	private static final String TAG = "DuneJellyfinBridge";
	private static final File NETWORK_MOUNT_LIST = new File("/tmp/run/network_mount_list.xml");
	private static final int HTTP_TIMEOUT_MILLIS = 2_000;
	private static final int MAX_VISITED_ENTRIES = 100_000;
	private static final int MAX_DIRECTORY_DEPTH = 64;

	String resolve(String httpMediaUrl, String suppliedFileName) {
		String fileName = baseName(suppliedFileName);
		if (fileName == null || fileName.isEmpty()) return null;

		long expectedSize = fetchContentLength(httpMediaUrl);
		for (NfsMount mount : readMountedNfsShares(NETWORK_MOUNT_LIST)) {
			File match = findFile(mount, fileName, expectedSize);
			if (match == null) continue;

			String nfsUrl = mount.toNfsUrl(match);
			if (nfsUrl != null) {
				Log.i(TAG, "Using mounted NFS media for " + fileName);
				return nfsUrl;
			}
		}

		Log.i(TAG, "No mounted NFS media found for " + fileName + "; using Jellyfin stream");
		return null;
	}

	private static File findFile(NfsMount mount, String fileName, long expectedSize) {
		File root = new File(mount.localPath);
		SearchState state = new SearchState(expectedSize);
		searchDirectory(root, fileName, 0, state);
		return state.exactSizeMatch != null ? state.exactSizeMatch : state.firstNameMatch;
	}

	private static boolean searchDirectory(File directory, String fileName, int depth, SearchState state) {
		if (depth > MAX_DIRECTORY_DEPTH || state.visitedEntries >= MAX_VISITED_ENTRIES) return false;

		String directoryPath = directory.getAbsolutePath();
		if (!state.visitedDirectories.add(directoryPath)) return false;

		File[] children;
		try {
			children = directory.listFiles();
		} catch (SecurityException error) {
			return false;
		}
		if (children == null) return false;

		List<File> directories = new ArrayList<>();
		for (File child : children) {
			if (++state.visitedEntries > MAX_VISITED_ENTRIES) return false;
			if (child.isDirectory()) {
				directories.add(child);
			} else if (fileName.equals(child.getName())) {
				if (state.firstNameMatch == null) state.firstNameMatch = child;
				if (state.expectedSize < 0 || child.length() == state.expectedSize) {
					state.exactSizeMatch = child;
					return true;
				}
			}
		}

		Collections.sort(directories, (first, second) -> {
			int priorityComparison = Integer.compare(directoryPriority(first), directoryPriority(second));
			return priorityComparison != 0
				? priorityComparison
				: String.CASE_INSENSITIVE_ORDER.compare(first.getName(), second.getName());
		});
		for (File child : directories) {
			if (searchDirectory(child, fileName, depth + 1, state)) return true;
		}
		return false;
	}

	private static int directoryPriority(File directory) {
		String name = directory.getName().toLowerCase(Locale.ROOT);
		if (name.equals("tv") || name.equals("shows") || name.equals("series")) return 0;
		if (name.equals("movies") || name.equals("films") || name.equals("anime")) return 1;
		return 2;
	}

	private static long fetchContentLength(String mediaUrl) {
		if (mediaUrl == null || !(mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://"))) {
			return -1;
		}

		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(mediaUrl).openConnection();
			connection.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
			connection.setReadTimeout(HTTP_TIMEOUT_MILLIS);
			connection.setRequestMethod("HEAD");
			connection.setUseCaches(false);
			int responseCode = connection.getResponseCode();
			if (responseCode < 200 || responseCode >= 300) return -1;

			String contentLength = connection.getHeaderField("Content-Length");
			if (contentLength == null) return -1;
			return Long.parseLong(contentLength);
		} catch (IOException | NumberFormatException error) {
			Log.d(TAG, "Unable to read Jellyfin media length", error);
			return -1;
		} finally {
			if (connection != null) connection.disconnect();
		}
	}

	private static List<NfsMount> readMountedNfsShares(File mountList) {
		List<NfsMount> mounts = new ArrayList<>();
		if (!mountList.isFile()) return mounts;

		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			Document document = factory.newDocumentBuilder().parse(mountList);
			NodeList nodes = document.getElementsByTagName("mount");
			for (int index = 0; index < nodes.getLength(); index++) {
				Node node = nodes.item(index);
				if (!(node instanceof Element)) continue;

				Element element = (Element) node;
				if (!"nfs".equals(element.getAttribute("type"))) continue;
				if (!"mounted".equals(element.getAttribute("status"))) continue;

				NfsMount mount = NfsMount.from(element);
				if (mount != null) mounts.add(mount);
			}
		} catch (IOException | ParserConfigurationException | SAXException | RuntimeException error) {
			Log.d(TAG, "Unable to read Dune NFS mounts", error);
		}

		Collections.sort(mounts, (first, second) -> Long.compare(second.mountTime, first.mountTime));
		return mounts;
	}

	static String baseName(String path) {
		if (path == null) return null;
		int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
		return slash >= 0 ? path.substring(slash + 1) : path;
	}

	static final class NfsMount {
		final String localPath;
		final String server;
		final String exportPath;
		final String protocol;
		final long mountTime;

		NfsMount(String localPath, String server, String exportPath, String protocol, long mountTime) {
			this.localPath = stripTrailingSlash(localPath);
			this.server = server;
			this.exportPath = normalizeAbsolutePath(exportPath);
			this.protocol = protocol;
			this.mountTime = mountTime;
		}

		private static NfsMount from(Element element) {
			String localPath = element.getAttribute("path");
			String server = element.getAttribute("server");
			String exportPath = element.getAttribute("dir");
			if (localPath.isEmpty() || server.isEmpty() || exportPath.isEmpty()) return null;

			long mountTime;
			try {
				mountTime = Long.parseLong(element.getAttribute("mount_tm"));
			} catch (NumberFormatException error) {
				mountTime = 0;
			}
			return new NfsMount(localPath, server, exportPath, element.getAttribute("proto"), mountTime);
		}

		String toNfsUrl(File mediaFile) {
			String mediaPath = mediaFile.getAbsolutePath();
			String rootPrefix = localPath + "/";
			if (!mediaPath.startsWith(rootPrefix) || containsControlCharacter(mediaPath)) return null;

			String relativePath = mediaPath.substring(localPath.length());
			String scheme;
			if ("tcp".equalsIgnoreCase(protocol)) scheme = "nfs-tcp";
			else if ("udp".equalsIgnoreCase(protocol)) scheme = "nfs-udp";
			else scheme = "nfs";

			return scheme + "://" + server + ":" + exportPath + ":" + relativePath;
		}

		private static String stripTrailingSlash(String path) {
			while (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
			return path;
		}

		private static String normalizeAbsolutePath(String path) {
			String normalized = path.startsWith("/") ? path : "/" + path;
			return stripTrailingSlash(normalized);
		}
	}

	private static boolean containsControlCharacter(String value) {
		for (int index = 0; index < value.length(); index++) {
			if (Character.isISOControl(value.charAt(index))) return true;
		}
		return false;
	}

	private static final class SearchState {
		final long expectedSize;
		final Set<String> visitedDirectories = new HashSet<>();
		int visitedEntries;
		File firstNameMatch;
		File exactSizeMatch;

		SearchState(long expectedSize) {
			this.expectedSize = expectedSize;
		}
	}
}
