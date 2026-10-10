package dev.dunehd.jellyfinbridge;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class NfsMediaResolverTest {
	@Rule
	public final TemporaryFolder mountRoot = new TemporaryFolder();

	@Test
	public void picksSameNamedFileWithExpectedSize() throws IOException {
		write("tv/Other Show/S01E01.mkv", "aaaa");
		File wanted = write("tv/Wanted Show/S01E01.mkv", "bbbbbbbb");

		assertEquals(wanted, NfsMediaResolver.findFile(mount(), "S01E01.mkv", 8));
	}

	@Test
	public void ignoresSameNamedFileWithDifferentSize() throws IOException {
		write("tv/Other Show/S01E01.mkv", "aaaa");

		assertNull(NfsMediaResolver.findFile(mount(), "S01E01.mkv", 999));
	}

	@Test
	public void matchesByNameWhenSizeIsUnknown() throws IOException {
		File only = write("movies/Movie/movie.mkv", "aaaa");

		assertEquals(only, NfsMediaResolver.findFile(mount(), "movie.mkv", -1));
	}

	@Test
	public void readsMountedNfsSharesNewestFirst() throws IOException {
		File mountList = mountRoot.newFile("network_mount_list.xml");
		Files.write(mountList.toPath(), ("<network_mounts>"
			+ "<mount path='/tmp/mnt/network/0' type='nfs' server='nas' dir='/old' proto='tcp' status='mounted' mount_tm='1'/>"
			+ "<mount path='/tmp/mnt/network/1' type='smb' server='nas' dir='/smb' status='mounted' mount_tm='9'/>"
			+ "<mount path='/tmp/mnt/network/2' type='nfs' server='nas' dir='/gone' proto='tcp' status='failed' mount_tm='9'/>"
			+ "<mount path='/tmp/mnt/network/3' type='nfs' server='nas' dir='/new' proto='tcp' status='mounted' mount_tm='5'/>"
			+ "</network_mounts>").getBytes(StandardCharsets.UTF_8));

		List<NfsMediaResolver.NfsMount> mounts = NfsMediaResolver.readMountedNfsShares(mountList);

		assertEquals(2, mounts.size());
		assertEquals("/new", mounts.get(0).exportPath);
		assertEquals("/old", mounts.get(1).exportPath);
	}

	private NfsMediaResolver.NfsMount mount() {
		return new NfsMediaResolver.NfsMount(mountRoot.getRoot().getPath(), "nas", "/media", "tcp", 1);
	}

	private File write(String relativePath, String content) throws IOException {
		File file = new File(mountRoot.getRoot(), relativePath);
		assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory());
		Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
		return file;
	}

	@Test
	public void extractsUnixAndWindowsBaseNames() {
		assertEquals("episode.mkv", NfsMediaResolver.baseName("/media/tv/episode.mkv"));
		assertEquals("episode.mkv", NfsMediaResolver.baseName("D:\\media\\tv\\episode.mkv"));
		assertEquals("episode.mkv", NfsMediaResolver.baseName("episode.mkv"));
		assertNull(NfsMediaResolver.baseName(null));
	}

	@Test
	public void mapsMountedFileToTcpNfsUrl() {
		NfsMediaResolver.NfsMount mount = new NfsMediaResolver.NfsMount(
			"/tmp/mnt/network/3/",
			"192.168.1.20",
			"/srv/media/",
			"tcp",
			1
		);

		assertEquals(
			"nfs-tcp://192.168.1.20:/srv/media:/tv/Series/episode.mkv",
			mount.toNfsUrl(new File("/tmp/mnt/network/3/tv/Series/episode.mkv"))
		);
	}

	@Test
	public void refusesFileOutsideMountedRoot() {
		NfsMediaResolver.NfsMount mount = new NfsMediaResolver.NfsMount(
			"/tmp/mnt/network/3",
			"192.168.1.20",
			"/srv/media",
			"tcp",
			1
		);

		assertNull(mount.toNfsUrl(new File("/tmp/mnt/network/30/episode.mkv")));
	}

	@Test
	public void mapsUnknownAndUdpProtocols() {
		File file = new File("/mnt/share/movie.mkv");

		assertEquals(
			"nfs-udp://nas:/media:/movie.mkv",
			new NfsMediaResolver.NfsMount("/mnt/share", "nas", "/media", "udp", 1).toNfsUrl(file)
		);
		assertEquals(
			"nfs://nas:/media:/movie.mkv",
			new NfsMediaResolver.NfsMount("/mnt/share", "nas", "/media", "", 1).toNfsUrl(file)
		);
	}
}
