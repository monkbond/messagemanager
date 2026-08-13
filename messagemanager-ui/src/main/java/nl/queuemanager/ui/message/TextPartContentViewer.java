package nl.queuemanager.ui.message;

import nl.queuemanager.core.configuration.CoreConfiguration;
import nl.queuemanager.jms.JMSPart;

import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

class TextPartContentViewer extends TextAreaContentViewer<JMSPart> implements MessagePartContentViewer {

	/** How much of a part to inspect when deciding whether it is text - a header's worth is plenty. */
	private static final int SNIFF_LIMIT = 8192;

	@Inject
	TextPartContentViewer(CoreConfiguration config) {
		super(config);
	}

	/**
	 * The part content exactly as it arrived - see {@link TextMessageContentViewer#getContent}.
	 */
	@Override
	public String getContent(JMSPart part) {
		Object content = part.getContent();
		if(content == null) {
			return "";
		}
		if(content instanceof byte[]) {
			// A part with no declared content type arrives as bytes; decoding with the
			// platform charset would corrupt anything outside it (see JMSPartImpl).
			return new String((byte[])content, StandardCharsets.UTF_8);
		}
		return content.toString();
	}

	/**
	 * Parts whose content type says text, plus parts that declare no content type at all.
	 * <p>
	 * A missing or empty content type used to fall through to the hex viewer, which showed a
	 * perfectly readable JSON or XML payload as a column of bytes. Message content is text
	 * far more often than not, so an undeclared part is treated as text unless it looks
	 * genuinely binary.
	 */
	public boolean supports(JMSPart part) {
		final String contentType = part.getContentType();

		if(contentType == null || contentType.trim().length() == 0) {
			return looksLikeText(part.getContent());
		}

		// Content types are case insensitive; comparing as-is sent "APPLICATION/JSON" to the
		// hex viewer for the same reason a blank one went there.
		final String type = contentType.trim().toLowerCase(Locale.ROOT);
		return type.startsWith("text/")
				|| type.startsWith("application/x-sonicxq-bpheader")
				|| type.startsWith("application/x-sonicxq-")
				|| type.startsWith("application/xml")
				|| type.startsWith("application/json");
	}

	/**
	 * Whether content with no declared type can be shown as text. Only content that is
	 * obviously binary is turned away: a NUL byte does not occur in text, while anything
	 * else - including bytes that are not valid UTF-8 - is more useful on screen as text
	 * than as hex.
	 */
	private static boolean looksLikeText(Object content) {
		if(!(content instanceof byte[])) {
			// A String (or anything else the part chose to hand out) is text by definition.
			return true;
		}

		final byte[] bytes = (byte[])content;
		final int limit = Math.min(bytes.length, SNIFF_LIMIT);
		for(int i = 0; i < limit; i++) {
			if(bytes[i] == 0) {
				return false;
			}
		}
		return true;
	}

	public String getDescription(JMSPart part) {
		final String contentType = part.getContentType();
		if(contentType == null || contentType.trim().length() == 0) {
			// Better than the empty parentheses this produced in the message tree.
			return "no content type";
		}
		return contentType;
	}
}
