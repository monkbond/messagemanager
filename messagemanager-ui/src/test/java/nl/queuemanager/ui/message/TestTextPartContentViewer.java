package nl.queuemanager.ui.message;

import nl.queuemanager.core.configuration.CoreConfiguration;
import nl.queuemanager.jms.JMSPart;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Which parts the text viewer claims, and how it reads their content.
 * <p>
 * A part with a missing or empty content type used to fall through to the hex viewer, which
 * showed a perfectly readable JSON payload as a column of bytes.
 */
public class TestTextPartContentViewer {

	/** The body from the message that turned this up, as bytes with an EMPTY content type. */
	private static final String JSON_BODY =
			"{\"status\":\"FAIL\",\"code\":455,\"errors\":\"Request is invalid.\"}";

	private TextPartContentViewer viewer;

	@Before
	public void setUp() {
		viewer = new TextPartContentViewer(mock(CoreConfiguration.class));
	}

	private static JMSPart part(final Object content, final String contentType) {
		return new JMSPart() {
			public Object getContent() { return content; }
			public String getContentType() { return contentType; }
			public byte[] getContentBytes() {
				return content instanceof byte[]
						? (byte[])content
						: String.valueOf(content).getBytes(StandardCharsets.UTF_8);
			}
			public String getHeaderField(String name) { return null; }
			public Enumeration<String> getHeaderFieldNames() { return null; }
			public void setContent(Object c, String t) {}
			public void setHeaderField(String n, String v) {}
		};
	}

	private static byte[] utf8(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	public void aPartWithoutAContentTypeIsTreatedAsText() {
		assertTrue(viewer.supports(part(utf8(JSON_BODY), "")));
		assertTrue(viewer.supports(part(utf8(JSON_BODY), "   ")));
		assertTrue("a null content type must not blow up either",
				viewer.supports(part(utf8(JSON_BODY), null)));
	}

	@Test
	public void aPartWithoutAContentTypeSaysSoInTheMessageTree() {
		assertEquals("no content type", viewer.getDescription(part(utf8(JSON_BODY), "")));
		assertEquals("no content type", viewer.getDescription(part(utf8(JSON_BODY), null)));
		assertEquals("text/xml", viewer.getDescription(part("x", "text/xml")));
	}

	/** Parts arrive as bytes; toString() on those would render "[B@1a2b3c". */
	@Test
	public void byteContentIsDecodedAsUtf8() {
		assertEquals(JSON_BODY, viewer.getContent(part(utf8(JSON_BODY), "")));
		assertEquals("Grüße € 中文", viewer.getContent(part(utf8("Grüße € 中文"), "")));
	}

	@Test
	public void stringContentIsUsedAsItIs() {
		assertEquals(JSON_BODY, viewer.getContent(part(JSON_BODY, "application/json")));
		assertEquals("", viewer.getContent(part(null, "text/plain")));
	}

	/**
	 * Content is text far more often than not, so only obviously binary content is turned
	 * away - a NUL byte does not occur in text.
	 */
	@Test
	public void obviouslyBinaryContentWithoutAContentTypeStaysWithTheHexViewer() {
		byte[] binary = new byte[] {0x50, 0x4b, 0x03, 0x04, 0x00, 0x00, 0x08};

		assertFalse(viewer.supports(part(binary, "")));
		assertFalse(viewer.supports(part(binary, null)));
	}

	@Test
	public void declaredTextContentTypesAreClaimed() {
		assertTrue(viewer.supports(part("x", "text/xml")));
		assertTrue(viewer.supports(part("x", "application/json")));
		assertTrue(viewer.supports(part("x", "application/x-sonicxq-bpheader")));
	}

	/** Content types are case insensitive - comparing as-is sent these to the hex viewer. */
	@Test
	public void contentTypesAreMatchedCaseInsensitively() {
		assertTrue(viewer.supports(part("x", "APPLICATION/JSON")));
		assertTrue(viewer.supports(part("x", "Text/Plain; charset=utf-8")));
	}

	@Test
	public void binaryContentTypesAreNotClaimed() {
		assertFalse(viewer.supports(part(new byte[] {1, 2, 3}, "application/pdf")));
		assertFalse(viewer.supports(part(new byte[] {1, 2, 3}, "image/png")));
	}
}
