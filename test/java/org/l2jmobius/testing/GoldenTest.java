package org.l2jmobius.testing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

/**
 * The lock is only worth something if it goes red when it should, and never rewrites what it is judged by.
 */
class GoldenTest
{
	private static final String NAME = "domain/case.tsv";

	@TempDir
	Path _goldenDir;

	@TempDir
	Path _actualDir;

	private Golden _golden;

	@BeforeEach
	void setUp()
	{
		_golden = new Golden(_goldenDir, _actualDir);
	}

	private void record(String... lines) throws IOException
	{
		Files.createDirectories(_goldenDir.resolve(NAME).getParent());
		Files.write(_goldenDir.resolve(NAME), List.of(lines));
	}

	@Test
	void identicalBehaviourPasses() throws IOException
	{
		record("a\t1", "b\t2");
		assertDoesNotThrow(() -> _golden.assertMatches(NAME, List.of("a\t1", "b\t2")));
	}

	@Test
	void aChangedLineFailsAndNamesIt() throws IOException
	{
		record("a\t1", "b\t2", "c\t3");
		final AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> _golden.assertMatches(NAME, List.of("a\t1", "b\t9", "c\t3")));
		assertTrue(failure.getMessage().contains("line 2"), failure.getMessage());
		assertTrue(failure.getMessage().contains("golden:   b\t2"), failure.getMessage());
		assertTrue(failure.getMessage().contains("observed: b\t9"), failure.getMessage());
		assertTrue(failure.getMessage().contains("1 of the first 3 lines differ"), failure.getMessage());
	}

	@Test
	void aMissingOrExtraLineFails() throws IOException
	{
		record("a\t1", "b\t2");
		assertThrows(AssertionFailedError.class, () -> _golden.assertMatches(NAME, List.of("a\t1")));
		assertThrows(AssertionFailedError.class, () -> _golden.assertMatches(NAME, List.of("a\t1", "b\t2", "c\t3")));
	}

	@Test
	void aLockWithoutAGoldenFailsInsteadOfRecordingOne()
	{
		final AssertionFailedError failure = assertThrows(AssertionFailedError.class, () -> _golden.assertMatches(NAME, List.of("a\t1")));
		assertTrue(failure.getMessage().contains("no golden recorded"), failure.getMessage());
		assertFalse(Files.exists(_goldenDir.resolve(NAME)), "the comparison must never create a golden");
	}

	@Test
	void observedBehaviourIsAlwaysWrittenForReviewAndTheGoldenNeverTouched() throws IOException
	{
		record("a\t1");
		assertThrows(AssertionFailedError.class, () -> _golden.assertMatches(NAME, List.of("a\t2")));
		assertEquals(List.of("a\t2"), Files.readAllLines(_actualDir.resolve(NAME)));
		assertEquals(List.of("a\t1"), Files.readAllLines(_goldenDir.resolve(NAME)));
	}

	@Test
	void aLineBreakInsideAFactIsRejected()
	{
		assertThrows(IllegalArgumentException.class, () -> _golden.assertMatches(NAME, List.of("a\n1")));
	}
}
