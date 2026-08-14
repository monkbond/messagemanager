package nl.queuemanager.ui.message;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Detection and formatting of message content. The cases here are the ones that made the
 * formatter necessary in the first place: content wrapped in a CDATA section, content that
 * arrives already indented, and content that does not parse at all.
 */
public class TestContentFormatter {

	// ------------------------------------------------------------- XML

	@Test
	public void xmlOnOneLineIsIndented() {
		ContentFormatter.Content content = ContentFormatter.analyze(
				"<order><id>1</id><customer><name>ACME</name></customer></order>");

		assertEquals(ContentFormatter.Kind.XML, content.getKind());
		assertTrue(content.isFormattable());
		assertTrue(content.getFormatted().contains("\n  <id>1</id>"));
		assertTrue(content.getFormatted().contains("\n    <name>ACME</name>"));
	}

	/**
	 * A serializer will not touch the content of a CDATA section - it is character data and
	 * must be reproduced verbatim - so a payload wrapped in one stays on a single line
	 * unless the formatter descends into it deliberately.
	 */
	@Test
	public void xmlInsideACdataSectionIsFormattedToo() {
		ContentFormatter.Content content = ContentFormatter.analyze(
				"<envelope><header>h</header><payload><![CDATA[<inner><a>1</a><b>2</b></inner>]]>"
						+ "</payload></envelope>");

		assertEquals(ContentFormatter.Kind.XML, content.getKind());
		assertTrue("CDATA markers are kept", content.getFormatted().contains("<![CDATA["));
		assertTrue("embedded content is laid out", content.getFormatted().contains("\n      <a>1</a>"));
		assertFalse("embedded content carries no XML declaration",
				content.getFormatted().contains("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n    <"));
	}

	@Test
	public void jsonInsideACdataSectionIsFormattedToo() {
		ContentFormatter.Content content = ContentFormatter.analyze(
				"<msg><body><![CDATA[{\"a\":1,\"b\":[1,2,3]}]]></body></msg>");

		assertTrue(content.getFormatted().contains("\"b\": ["));
	}

	/**
	 * Whitespace already in the source used to be indented AGAIN by the serializer, so an
	 * already-formatted message came out ragged with stray blank lines.
	 */
	@Test
	public void alreadyIndentedXmlIsReIndentedCleanly() {
		ContentFormatter.Content content = ContentFormatter.analyze(
				"<root>\n  <a>1</a>\n     <b>2</b>\n</root>");

		assertFalse("no stray blank lines", content.getFormatted().contains("\n\n"));
		assertTrue(content.getFormatted().contains("\n  <a>1</a>"));
		assertTrue(content.getFormatted().contains("\n  <b>2</b>"));
	}

	// ------------------------------------------------------------ JSON

	@Test
	public void minifiedJsonIsIndented() {
		ContentFormatter.Content content = ContentFormatter.analyze(
				"{\"id\":1,\"tags\":[\"a\",\"b\"],\"nested\":{\"k\":null}}");

		assertEquals(ContentFormatter.Kind.JSON, content.getKind());
		assertTrue(content.isFormattable());
		assertTrue(content.getFormatted().contains("\n  \"id\": 1,"));
		assertTrue(content.getFormatted().contains("\n    \"a\","));
	}

	@Test
	public void emptyObjectsAndArraysStayOnOneLine() {
		ContentFormatter.Content content = ContentFormatter.analyze("{\"a\":{},\"b\":[]}");

		assertTrue(content.getFormatted().contains("\"a\": {}"));
		assertTrue(content.getFormatted().contains("\"b\": []"));
	}

	/** Structural characters inside a string must not be read as structure. */
	@Test
	public void jsonStringContentIsLeftAlone() {
		ContentFormatter.Content content = ContentFormatter.analyze(
				"{\"a\":\"{not:a,brace}\",\"b\":\"he said \\\"hi\\\"\",\"c\":\"back\\\\slash\"}");

		assertEquals(ContentFormatter.Kind.JSON, content.getKind());
		assertTrue(content.getFormatted().contains("\"{not:a,brace}\""));
		assertTrue(content.getFormatted().contains("he said \\\"hi\\\""));
		assertTrue(content.getFormatted().contains("back\\\\slash"));
	}

	@Test
	public void alreadyFormattedJsonOffersNoSwitch() {
		assertFalse(ContentFormatter.analyze("{\n  \"a\": 1\n}").isFormattable());
	}

	// ------------------------------------------------------- malformed

	@Test
	public void malformedJsonIsReportedRatherThanFormatted() {
		ContentFormatter.Content content = ContentFormatter.analyze("{\"a\":1,\"b\":[1,2}");

		assertEquals("still recognised as JSON", ContentFormatter.Kind.JSON, content.getKind());
		assertTrue(content.isMalformed());
		assertTrue("the switch is still offered, to show the explanation", content.isFormattable());
		assertTrue(content.getProblem(), content.getProblem().contains("'}'"));
		assertTrue(content.getProblem(), content.getProblem().contains("line 1, column 16"));
		assertTrue("the note is a JSON line comment", content.getFormatted().startsWith("//"));
		assertTrue("the content itself is untouched", content.getFormatted().endsWith(content.getRaw()));
	}

	@Test
	public void unterminatedJsonStringIsExplained() {
		ContentFormatter.Content content = ContentFormatter.analyze("{\"a\":\"unterminated");

		assertTrue(content.isMalformed());
		assertTrue(content.getProblem(), content.getProblem().contains("never closed"));
	}

	@Test
	public void malformedXmlIsReportedRatherThanFormatted() {
		ContentFormatter.Content content = ContentFormatter.analyze("<root><a>1</a>");

		assertEquals("still recognised as XML", ContentFormatter.Kind.XML, content.getKind());
		assertTrue(content.isMalformed());
		assertTrue(content.getProblem(), content.getProblem().contains("line 1"));
		assertEquals("the raw content is preserved", "<root><a>1</a>", content.getRaw());
		assertTrue("the content itself is untouched", content.getFormatted().endsWith(content.getRaw()));
	}

	/** "--" may not appear inside an XML comment, and parser messages contain it easily. */
	@Test
	public void dashesInAParserMessageDoNotBreakTheXmlComment() {
		ContentFormatter.Content content = ContentFormatter.analyze("<a>-- unclosed");

		assertTrue(content.isMalformed());
		String comment = content.getFormatted().substring(0, content.getFormatted().indexOf("-->"));
		assertFalse("no stray '--' inside the comment", comment.replace("<!--", "").contains("--"));
	}

	/**
	 * A note inside a CDATA section would be writing our own words into the message's data,
	 * so embedded content that does not parse is left exactly as it is.
	 */
	@Test
	public void malformedContentInsideCdataIsLeftAlone() {
		ContentFormatter.Content content = ContentFormatter.analyze(
				"<m><b><![CDATA[{\"a\":1,\"b\":[1,2}]]></b></m>");

		assertFalse("no note injected", content.getFormatted().contains("//"));
		assertTrue("left verbatim", content.getFormatted().contains("{\"a\":1,\"b\":[1,2}"));
	}

	// ----------------------------------------------------------- other

	@Test
	public void plainTextOffersNoSwitch() {
		ContentFormatter.Content content = ContentFormatter.analyze("just some log output, not markup");

		assertEquals(ContentFormatter.Kind.PLAIN, content.getKind());
		assertFalse(content.isMalformed());
		assertFalse(content.isFormattable());
	}

	@Test
	public void rawContentIsAlwaysPreservedExactly() {
		String original = "<order><id>1</id></order>";

		assertEquals(original, ContentFormatter.analyze(original).getRaw());
	}

	@Test
	public void nullAndEmptyContentAreHandled() {
		assertEquals("", ContentFormatter.analyze(null).getRaw());
		assertFalse(ContentFormatter.analyze(null).isFormattable());
		assertFalse(ContentFormatter.analyze("").isFormattable());
	}
}
