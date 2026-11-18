import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * Downloads every {@code file} and {@code jar} entry of toolchain.lock and refuses any byte that does not match its pinned digest.
 * <p>
 * Runs once, while the build image is assembled, as a single-file program on the pinned JDK, so the image needs no curl, no package manager and no build tool to provision itself.
 * </p>
 * Usage: {@code java Provision.java <toolchain.lock> <output-dir>}
 */
public final class Provision
{
	private static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

	private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();

	private Provision()
	{
	}

	public static void main(String[] args) throws Exception
	{
		if (args.length != 2)
		{
			throw new IllegalArgumentException("usage: java Provision.java <toolchain.lock> <output-dir>");
		}

		final Path out = Path.of(args[1]);
		final Path files = Files.createDirectories(out.resolve("files"));
		final Path testLibs = Files.createDirectories(out.resolve("test-libs"));

		final List<String> lines = Files.readAllLines(Path.of(args[0]));
		for (int i = 0; i < lines.size(); i++)
		{
			final String line = lines.get(i).strip();
			if (line.isEmpty() || line.startsWith("#"))
			{
				continue;
			}

			final String[] f = line.split("\\s+");
			try
			{
				switch (f[0])
				{
					case "image" -> require(f, 4); // Consumed by build.sh and the test harness, not here.
					case "file" ->
					{
						require(f, 4);
						fetch(URI.create(f[3]), f[2], files.resolve(f[1]));
					}
					case "jar" ->
					{
						require(f, 3);
						final String[] gav = f[1].split(":");
						if (gav.length != 3)
						{
							throw new IllegalArgumentException("jar name must be group:artifact:version");
						}
						final String jar = gav[1] + "-" + gav[2] + ".jar";
						fetch(URI.create(MAVEN_CENTRAL + gav[0].replace('.', '/') + "/" + gav[1] + "/" + gav[2] + "/" + jar), f[2], testLibs.resolve(jar));
					}
					default -> throw new IllegalArgumentException("unknown kind '" + f[0] + "'");
				}
			}
			catch (Exception e)
			{
				throw new IllegalStateException(args[0] + ":" + (i + 1) + ": " + e.getMessage(), e);
			}
		}
	}

	private static void require(String[] fields, int count)
	{
		if (fields.length != count)
		{
			throw new IllegalArgumentException("expected " + count + " fields, found " + fields.length);
		}
	}

	/**
	 * Streams {@code uri} into {@code target}, hashing on the way. The file only appears under its final name after the digest matched.
	 */
	private static void fetch(URI uri, String pin, Path target) throws IOException, InterruptedException, java.security.NoSuchAlgorithmException
	{
		final int colon = pin.indexOf(':');
		if (colon < 0)
		{
			throw new IllegalArgumentException("digest must be <algorithm>:<hex>, got '" + pin + "'");
		}
		final String algorithm = switch (pin.substring(0, colon))
		{
			case "sha256" -> "SHA-256";
			case "sha512" -> "SHA-512";
			default -> throw new IllegalArgumentException("unsupported digest algorithm in '" + pin + "'");
		};
		final String expected = pin.substring(colon + 1);

		final HttpResponse<InputStream> response = HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200)
		{
			response.body().close();
			throw new IOException("HTTP " + response.statusCode() + " for " + uri);
		}

		final MessageDigest digest = MessageDigest.getInstance(algorithm);
		final Path partial = target.resolveSibling(target.getFileName() + ".partial");
		try (InputStream in = new DigestInputStream(response.body(), digest))
		{
			Files.copy(in, partial);
		}

		final String actual = HexFormat.of().formatHex(digest.digest());
		if (!actual.equals(expected))
		{
			Files.delete(partial);
			throw new IOException("digest mismatch for " + uri + ": pinned " + expected + ", downloaded " + actual);
		}

		Files.move(partial, target);
		System.out.println("verified " + target.getFileName() + "  " + pin);
	}
}
