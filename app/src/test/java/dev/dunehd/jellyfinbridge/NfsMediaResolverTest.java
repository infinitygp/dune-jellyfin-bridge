package dev.dunehd.jellyfinbridge;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public final class NfsMediaResolverTest {
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
			"192.168.69.20",
			"/srv/data/media/",
			"tcp",
			1
		);

		assertEquals(
			"nfs-tcp://192.168.69.20:/srv/data/media:/tv/Series/episode.mkv",
			mount.toNfsUrl(new File("/tmp/mnt/network/3/tv/Series/episode.mkv"))
		);
	}

	@Test
	public void refusesFileOutsideMountedRoot() {
		NfsMediaResolver.NfsMount mount = new NfsMediaResolver.NfsMount(
			"/tmp/mnt/network/3",
			"192.168.69.20",
			"/srv/data/media",
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
