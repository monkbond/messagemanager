/**

 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.queuemanager.ui.message;

import nl.queuemanager.core.util.NullEntityResolver;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Determines what kind of content a message body holds and, when it is structured,
 * produces a pretty-printed version of it.
 * <p>
 * Formatting is strictly a <em>view</em> of the content: it inserts whitespace that is
 * not in the message. Inside a CDATA section that whitespace is significant data, so a
 * formatted body must never be written back, forwarded or saved - which is exactly why
 * the viewer keeps the raw content available alongside it.
 * <p>
 * Content that LOOKS like XML or JSON but does not parse is reported as that kind with a
 * {@link Content#getProblem() problem} describing why, rather than being passed off as
 * plain text. "This is broken, and here is where" is the answer the user needs when a
 * message will not format; the raw view still shows the content untouched.
 *
 * @see TextAreaContentViewer
 */
final class ContentFormatter {

	private ContentFormatter() {}

	/** Indentation used for both XML and JSON. */
	private static final String INDENT = "  ";

	/**
	 * How far to descend into content embedded in other content (JSON inside a CDATA
	 * section inside XML, ...). A limit keeps pathologically nested input from recursing
	 * without bound; three levels is well past anything seen in practice.
	 */
	private static final int MAX_NESTING = 3;

	enum Kind {
		XML(SyntaxConstants.SYNTAX_STYLE_XML),
		JSON(SyntaxConstants.SYNTAX_STYLE_JSON),
		PLAIN(SyntaxConstants.SYNTAX_STYLE_NONE);

		private final String syntaxStyle;

		Kind(String syntaxStyle) {
			this.syntaxStyle = syntaxStyle;
		}

		String getSyntaxStyle() {
			return syntaxStyle;
		}
	}

	/** Raised when content of a known kind cannot be parsed, carrying what was wrong with it. */
	private static final class MalformedException extends Exception {
		private static final long serialVersionUID = 1L;

		MalformedException(String message) {
			super(message);
		}
	}

	/** The content of a message body in both the forms the viewer can display. */
	static final class Content {
		private final String raw;
		private final String formatted;
		private final Kind kind;
		private final String problem;

		private Content(String raw, String formatted, Kind kind, String problem) {
			this.raw = raw;
			this.formatted = formatted;
			this.kind = kind;
			this.problem = problem;
		}

		String getRaw() {
			return raw;
		}

		/** The pretty-printed content, or the raw content when there is nothing to format. */
		String getFormatted() {
			return formatted != null ? formatted : raw;
		}

		Kind getKind() {
			return kind;
		}

		/**
		 * Why this content could not be formatted, or null when it formatted fine. Set only
		 * for content that looks like XML or JSON but does not parse.
		 */
		String getProblem() {
			return problem;
		}

		boolean isMalformed() {
			return problem != null;
		}

		/**
		 * Whether the formatted view shows something different from the raw one - either a
		 * pretty-printed version, or a note explaining why there is none. When it does not,
		 * there is nothing to switch between and the viewer offers no switch.
		 */
		boolean isFormattable() {
			return formatted != null && !formatted.equals(raw);
		}
	}

	/**
	 * Detect the kind of the given content, pretty-print it when possible, and report why
	 * when it looked structured but could not be parsed.
	 */
	static Content analyze(String raw) {
		return analyze(raw, 0);
	}

	private static Content analyze(String raw, int nesting) {
		if(raw == null) {
			return new Content("", null, Kind.PLAIN, null);
		}

		final String trimmed = raw.trim();

		if(trimmed.startsWith("<")) {
			try {
				return new Content(raw, formatXml(raw, nesting, nesting == 0), Kind.XML, null);
			} catch (MalformedException e) {
				return malformed(raw, Kind.XML, e.getMessage(), nesting);
			}
		}

		if(trimmed.startsWith("{") || trimmed.startsWith("[")) {
			try {
				return new Content(raw, formatJson(raw), Kind.JSON, null);
			} catch (MalformedException e) {
				return malformed(raw, Kind.JSON, e.getMessage(), nesting);
			}
		}

		return new Content(raw, null, Kind.PLAIN, null);
	}

	/**
	 * Content of a known kind that did not parse. The formatted view becomes the content
	 * with a note explaining the problem, so a message that will not format says why
	 * instead of just looking like unformatted text.
	 * <p>
	 * EMBEDDED content is left completely alone: a note injected into a CDATA section would
	 * be writing our own words into what is supposed to be the message's data.
	 */
	private static Content malformed(String raw, Kind kind, String problem, int nesting) {
		if(nesting > 0) {
			return new Content(raw, null, Kind.PLAIN, null);
		}
		return new Content(raw, note(kind, problem) + raw, kind, problem);
	}

	/**
	 * The explanation shown above malformed content, as a comment of the content's own kind
	 * so it reads as an annotation rather than as part of the message.
	 */
	private static String note(Kind kind, String problem) {
		final String text = "This content looks like " + (kind == Kind.XML ? "XML" : "JSON")
				+ " but could not be parsed, so it cannot be formatted:\n"
				+ problem + "\n"
				+ "The content below is shown unchanged - switch to Raw to see it without this note.";

		if(kind == Kind.JSON) {
			StringBuilder comment = new StringBuilder();
			for(String line: text.split("\n")) {
				comment.append("// ").append(line).append('\n');
			}
			return comment.toString();
		}

		// "--" may not appear inside an XML comment, and it can easily turn up in a parser
		// message, so break up any run of dashes before wrapping the text.
		return "<!--\n" + text.replace("--", "- -") + "\n-->\n";
	}

	// ---------------------------------------------------------------- XML

	/**
	 * @param keepDeclaration whether to keep the XML declaration - dropped for content
	 *                        embedded in a parent document, where it would only be noise.
	 */
	private static String formatXml(String xml, int nesting, boolean keepDeclaration)
			throws MalformedException {
		final Document document;
		try {
			document = parse(xml);
		} catch (SAXParseException e) {
			throw new MalformedException(describe(e));
		} catch (Exception e) {
			throw new MalformedException(e.toString());
		}

		try {
			// Whitespace the source already contains would be indented AGAIN by the
			// serializer, which is what made an already-formatted message come out ragged.
			// Note this also drops whitespace between elements in mixed content; the raw
			// view is the answer for anyone who needs to see the original layout.
			stripWhitespaceOnlyText(document);

			if(nesting < MAX_NESTING) {
				// The root element sits at indent level 0, so its children are at level 1.
				formatEmbeddedContent(document.getDocumentElement(), 0, nesting);
			}
			return serialize(document, keepDeclaration);
		} catch (Exception e) {
			// The document parsed but could not be rendered - report it the same way, the
			// user still ends up looking at the raw content either way.
			throw new MalformedException(e.toString());
		}
	}

	/** A parser complaint in the form the user needs: what is wrong, and where. */
	private static String describe(SAXParseException e) {
		String where = "";
		if(e.getLineNumber() > 0) {
			where = " (line " + e.getLineNumber()
					+ (e.getColumnNumber() > 0 ? ", column " + e.getColumnNumber() : "") + ")";
		}
		return e.getMessage() + where;
	}

	private static Document parse(String xml) throws Exception {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		// Message content is untrusted input: opening a message must never turn into a
		// network fetch or a local file read because the body references an external entity.
		dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
		dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
		dbf.setXIncludeAware(false);
		dbf.setExpandEntityReferences(false);

		DocumentBuilder db = dbf.newDocumentBuilder();
		db.setEntityResolver(new NullEntityResolver());
		// Content that is not XML is an ordinary outcome here (any text body starting with
		// '<'), so report it by throwing rather than letting the parser print to stderr.
		db.setErrorHandler(new ErrorHandler() {
			public void warning(SAXParseException e) { /* not interesting for formatting */ }
			public void error(SAXParseException e) throws SAXException { throw e; }
			public void fatalError(SAXParseException e) throws SAXException { throw e; }
		});
		return db.parse(new InputSource(new StringReader(xml)));
	}

	/**
	 * Remove text nodes that hold nothing but whitespace, so the serializer indents from a
	 * clean tree. CDATA sections are left alone: whitespace inside one is content, not layout.
	 */
	private static void stripWhitespaceOnlyText(Document document) throws Exception {
		XPath xpath = XPathFactory.newInstance().newXPath();
		NodeList nodes = (NodeList) xpath.evaluate(
				"//text()[normalize-space(.)='']", document, XPathConstants.NODESET);

		// Collect first: removing while iterating a live NodeList skips nodes.
		List<Node> removable = new ArrayList<Node>();
		for(int i = 0; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if(node.getNodeType() != Node.CDATA_SECTION_NODE && node.getParentNode() != null) {
				removable.add(node);
			}
		}
		for(Node node: removable) {
			node.getParentNode().removeChild(node);
		}
	}

	/**
	 * Pretty-print XML or JSON that is itself carried inside a CDATA section or text node.
	 * No XML serializer will do this: the content of a CDATA section is character data and
	 * must be reproduced verbatim, so a payload wrapped in one stays on a single line no
	 * matter how the document around it is indented.
	 */
	private static void formatEmbeddedContent(Node parent, int depth, int nesting) {
		if(parent == null) {
			return;
		}

		for(Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
			final short type = child.getNodeType();

			if(type == Node.ELEMENT_NODE) {
				formatEmbeddedContent(child, depth + 1, nesting);
				continue;
			}

			if(type != Node.CDATA_SECTION_NODE && type != Node.TEXT_NODE) {
				continue;
			}

			final String value = child.getNodeValue();
			if(value == null || value.trim().length() == 0) {
				continue;
			}

			Content nested = analyze(value, nesting + 1);
			if(nested.isFormattable()) {
				child.setNodeValue(indentBlock(nested.getFormatted(), depth + 1));
			}
		}
	}

	/**
	 * Lay a formatted block out on its own lines at the given depth, so embedded content
	 * lines up with the element that carries it instead of starting mid-line.
	 */
	private static String indentBlock(String content, int depth) {
		final String indent = INDENT.repeat(depth);
		StringBuilder result = new StringBuilder(content.length() + 64);

		result.append('\n');
		for(String line: content.split("\n", -1)) {
			if(line.trim().length() == 0) {
				result.append('\n');
			} else {
				result.append(indent).append(line).append('\n');
			}
		}
		result.append(INDENT.repeat(Math.max(0, depth - 1)));
		return result.toString();
	}

	private static String serialize(Document document, boolean keepDeclaration) throws Exception {
		Transformer transformer = TransformerFactory.newInstance().newTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		// INDENT alone only asks the serializer to indent - without this it uses its own
		// default width rather than the one used everywhere else in the formatted view.
		transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount",
				String.valueOf(INDENT.length()));
		if(!keepDeclaration) {
			transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
		}

		StringWriter writer = new StringWriter();
		transformer.transform(new DOMSource(document), new StreamResult(writer));
		return writer.toString().trim();
	}

	// --------------------------------------------------------------- JSON

	/**
	 * Pretty-print JSON without a JSON parser - the application ships none, and pulling one
	 * in for indentation would add a dependency (and a module requirement) for a display
	 * concern. This walks the text, tracking only strings and nesting, and gives up the
	 * moment the structure does not add up so that malformed content is never shown through
	 * a formatter that misread it.
	 */
	private static String formatJson(String json) throws MalformedException {
		final StringBuilder out = new StringBuilder(json.length() + 64);
		final char[] stack = new char[256];

		int depth = 0;
		boolean inString = false;
		boolean escaped = false;

		for(int i = 0; i < json.length(); i++) {
			final char c = json.charAt(i);

			if(inString) {
				out.append(c);
				if(escaped) {
					escaped = false;
				} else if(c == '\\') {
					escaped = true;
				} else if(c == '"') {
					inString = false;
				}
				continue;
			}

			switch(c) {
			case '"':
				inString = true;
				out.append(c);
				break;

			case '{':
			case '[': {
				if(depth >= stack.length) {
					throw new MalformedException("Nested more than " + stack.length
							+ " levels deep" + at(json, i));
				}
				out.append(c);
				final char closer = c == '{' ? '}' : ']';
				final int next = nextNonWhitespace(json, i + 1);
				if(next != -1 && json.charAt(next) == closer) {
					// Keep an empty object or array on one line rather than splitting it.
					out.append(closer);
					i = next;
					break;
				}
				stack[depth++] = closer;
				newLine(out, depth);
				break;
			}

			case '}':
			case ']':
				if(depth == 0) {
					throw new MalformedException("Unexpected '" + c
							+ "' - there is nothing open to close" + at(json, i));
				}
				if(stack[depth - 1] != c) {
					throw new MalformedException("Found '" + c + "' where '" + stack[depth - 1]
							+ "' was expected" + at(json, i));
				}
				depth--;
				newLine(out, depth);
				out.append(c);
				break;

			case ',':
				if(depth == 0) {
					throw new MalformedException("Unexpected ',' outside any object or array"
							+ at(json, i));
				}
				out.append(c);
				newLine(out, depth);
				break;

			case ':':
				out.append(c).append(' ');
				break;

			default:
				if(!Character.isWhitespace(c)) {
					out.append(c);
				}
				// Whitespace outside a string is layout, not content - drop it and re-add
				// our own. This is what turns a minified payload into something readable.
				break;
			}
		}

		if(inString) {
			throw new MalformedException("A text value is never closed - the content ends "
					+ "inside a string");
		}
		if(depth != 0) {
			throw new MalformedException(depth + (depth == 1 ? " object or array is" : " objects "
					+ "or arrays are") + " never closed - the content ends too early");
		}
		return out.toString();
	}

	/** Where in the content something went wrong, in the terms an editor shows. */
	private static String at(String content, int index) {
		int line = 1;
		int column = 1;
		for(int i = 0; i < index && i < content.length(); i++) {
			if(content.charAt(i) == '\n') {
				line++;
				column = 1;
			} else {
				column++;
			}
		}
		return " (line " + line + ", column " + column + ")";
	}

	private static void newLine(StringBuilder out, int depth) {
		out.append('\n').append(INDENT.repeat(depth));
	}

	private static int nextNonWhitespace(String s, int from) {
		for(int i = from; i < s.length(); i++) {
			if(!Character.isWhitespace(s.charAt(i))) {
				return i;
			}
		}
		return -1;
	}
}
