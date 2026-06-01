/*
 * Copyright 2025-2026 Hancom Inc.
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
package org.opendataloader.pdf.html;

import org.opendataloader.pdf.api.Config;
import org.opendataloader.pdf.containers.StaticLayoutContainers;
import org.opendataloader.pdf.entities.SemanticFormula;
import org.opendataloader.pdf.entities.EnrichedImageChunk;
import org.opendataloader.pdf.entities.SemanticPicture;
import org.opendataloader.pdf.markdown.MarkdownSyntax;
import org.opendataloader.pdf.processors.DocumentProcessor;
import org.opendataloader.pdf.utils.Base64ImageUtils;
import org.opendataloader.pdf.utils.GeneratorUtils;
import org.opendataloader.pdf.utils.ImagesUtils;
import org.opendataloader.pdf.utils.OutputType;
import org.verapdf.wcag.algorithms.entities.*;
import org.verapdf.wcag.algorithms.entities.content.*;
import org.verapdf.wcag.algorithms.entities.enums.TextAlignment;
import org.verapdf.wcag.algorithms.entities.lists.ListItem;
import org.verapdf.wcag.algorithms.entities.lists.PDFList;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorder;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderCell;
import org.verapdf.wcag.algorithms.entities.tables.tableBorders.TableBorderRow;
import org.verapdf.wcag.algorithms.semanticalgorithms.containers.StaticContainers;
import org.verapdf.wcag.algorithms.semanticalgorithms.utils.NodeUtils;

import java.awt.Color;
import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Generates HTML output from PDF document content.
 * Converts semantic elements like paragraphs, headings, tables, and images into HTML format.
 */
public class HtmlGenerator implements Closeable {

    /** Logger for this class. */
    protected static final Logger LOGGER = Logger.getLogger(HtmlGenerator.class.getCanonicalName());

    /** Writer for the HTML output file. */
    protected final FileWriter htmlWriter;
    /** Name of the input PDF file. */
    protected final String pdfFileName;
    /** Absolute path to the input PDF file. */
    protected final Path pdfFilePath;
    /** Name of the output HTML file. */
    protected final String htmlFileName;
    /** Absolute path to the output HTML file. */
    protected final Path htmlFilePath;
    /** Current table nesting level for tracking nested tables. */
    protected int tableNesting = 0;
    /** String to insert between pages in HTML output. */
    protected String htmlPageSeparator = "";
    /**
     * Page numbers (1-based) selected by --pages; an empty set means all pages.
     * Sourced from the raw {@link Config#getPageNumbers()} list (not the
     * validated set built by {@code DocumentProcessor.getValidPageNumbers}).
     * Safe to compare against {@code pageNumber + 1} because the surrounding
     * loop is bounded by the document's actual page count, so out-of-range
     * values from the raw list are never tested for membership.
     */
    protected final Set<Integer> selectedPageNumbers;
    /** Whether to embed images as Base64 data URIs. */
    protected boolean embedImages = false;
    /** Format for extracted images (png or jpeg). */
    protected String imageFormat = Config.IMAGE_FORMAT_PNG;
    /** Whether to include page headers and footers in output. */
    protected boolean includeHeaderFooter = false;

    protected double pagesHeight = 0.0;

    protected Stack<IObject> nestedObjects = new Stack<>();

    protected static boolean isHeading = false;

    /**
     * Creates a new HtmlGenerator for the specified PDF file.
     *
     * @param inputPdf the input PDF file
     * @param config the configuration settings
     * @throws IOException if unable to create the output file
     */
    public HtmlGenerator(File inputPdf, Config config) throws IOException {
        this.pdfFileName = inputPdf.getName();
        this.pdfFilePath = inputPdf.toPath().toAbsolutePath();
        this.htmlFileName = pdfFileName.substring(0, pdfFileName.length() - 3) + "html";
        this.htmlFilePath = Path.of(config.getOutputFolder(), htmlFileName);
        this.htmlWriter = new FileWriter(htmlFilePath.toFile(), StandardCharsets.UTF_8);
        this.htmlPageSeparator = config.getHtmlPageSeparator();
        this.selectedPageNumbers = new HashSet<>(config.getPageNumbers());
        this.embedImages = config.isEmbedImages();
        this.imageFormat = config.getImageFormat();
        this.includeHeaderFooter = config.isIncludeHeaderFooter();
    }

    /**
     * Writes the document contents to HTML format.
     *
     * @param contents the document contents organized by page
     */
    public void writeToHtml(List<List<IObject>> contents) {
        try {
            htmlWriter.write("<!DOCTYPE html>\n");
            htmlWriter.write("<html lang=\"und\">\n<head>\n<meta charset=\"utf-8\">\n");
            htmlWriter.write("<title>" + pdfFileName + "</title>\n");
            writeStyleTag();
            htmlWriter.write("</head>\n<body>\n");

            for (int pageNumber = 0; pageNumber < StaticContainers.getDocument().getNumberOfPages(); pageNumber++) {
                if (selectedPageNumbers.isEmpty() || selectedPageNumbers.contains(pageNumber + 1)) {
                    pagesHeight += Objects.requireNonNull(DocumentProcessor.getPageBoundingBox(pageNumber)).getHeight();
                    writePageSeparator(pageNumber);
                }
                for (IObject content : contents.get(pageNumber)) {
                    this.write(content);
                }
            }

            htmlWriter.write("\n</body>\n</html>");
            LOGGER.log(Level.INFO, "Created {0}", htmlFilePath);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Unable to create html output: " + e.getMessage());
        }
    }

    protected void writeStyleTag() throws IOException {
        htmlWriter.write("<style>\n");
        htmlWriter.write(String.format(HtmlSyntax.HTML_STYLE_TAG_ENTRY_OPENING, "ul, p, h1, h2, h3, h4, h5, h6, table, figcaption, img, li, figure"));
        htmlWriter.write(String.format(HtmlSyntax.HTML_STYLE_TAG_ENTRY_VALUE, HtmlSyntax.HTML_POSITION_ABSOLUTE_PROPERTY));
        htmlWriter.write(String.format(HtmlSyntax.HTML_STYLE_TAG_ENTRY_VALUE, HtmlSyntax.HTML_ZERO_MARGIN_PROPERTY));
        htmlWriter.write(String.format(HtmlSyntax.HTML_STYLE_TAG_ENTRY_VALUE, HtmlSyntax.HTML_LINE_HEIGHT_PROPERTY));
        htmlWriter.write(HtmlSyntax.HTML_STYLE_TAG_ENTRY_CLOSING);

        htmlWriter.write(String.format(HtmlSyntax.HTML_STYLE_TAG_ENTRY_OPENING, "ul"));
        htmlWriter.write(String.format(HtmlSyntax.HTML_STYLE_TAG_ENTRY_VALUE, HtmlSyntax.HTML_LIST_STYLE_TYPE_NONE_PROPERTY));
        htmlWriter.write(String.format(HtmlSyntax.HTML_STYLE_TAG_ENTRY_VALUE, HtmlSyntax.HTML_PADDING_ZERO_PROPERTY));
        htmlWriter.write(String.format(HtmlSyntax.HTML_STYLE_TAG_ENTRY_VALUE, HtmlSyntax.HTML_WIDTH_100_PROPERTY));
        htmlWriter.write(HtmlSyntax.HTML_STYLE_TAG_ENTRY_CLOSING);

        htmlWriter.write(String.format(HtmlSyntax.HTML_STYLE_TAG_ENTRY_OPENING, "table"));
        htmlWriter.write(String.format(HtmlSyntax.HTML_STYLE_TAG_ENTRY_VALUE, HtmlSyntax.HTML_BORDER_COLLAPSE_PROPERTY));
        htmlWriter.write(HtmlSyntax.HTML_STYLE_TAG_ENTRY_CLOSING);
        htmlWriter.write(String.format(HtmlSyntax.HTML_STYLE_TAG_ENTRY_OPENING, "span"));
        htmlWriter.write(String.format(HtmlSyntax.HTML_STYLE_TAG_ENTRY_VALUE, HtmlSyntax.HTML_VERTICAL_ALIGN_TOP_PROPERTY));
        htmlWriter.write(HtmlSyntax.HTML_STYLE_TAG_ENTRY_CLOSING);

        htmlWriter.write("</style>\n");
    }

    /**
     * Writes a page separator to the HTML output if configured.
     *
     * @param pageNumber the current page number (0-indexed)
     * @throws IOException if unable to write to the output
     */
    //TODO update for formatted html
    protected void writePageSeparator(int pageNumber) throws IOException {
        if (!htmlPageSeparator.isEmpty()) {
            htmlWriter.write(htmlPageSeparator.contains(Config.PAGE_NUMBER_STRING)
                ? htmlPageSeparator.replace(Config.PAGE_NUMBER_STRING, String.valueOf(pageNumber + 1))
                : htmlPageSeparator);
            htmlWriter.write("\n");
        }
    }

    /**
     * Writes a single content object to the HTML output.
     *
     * @param object the content object to write
     * @throws IOException if unable to write to the output
     */
    protected void write(IObject object) throws IOException {
        if (object instanceof SemanticHeaderOrFooter) {
            if (includeHeaderFooter) {
                writeHeaderOrFooter((SemanticHeaderOrFooter) object);
            }
            return;
        } else if (object instanceof SemanticPicture) {
            writePicture((SemanticPicture) object);
        } else if (object instanceof ImageChunk) {
            writeImage((ImageChunk) object);
        } else if (object instanceof SemanticFormula) {
            writeFormula((SemanticFormula) object);
        } else if (object instanceof SemanticHeading) {
            writeHeading((SemanticHeading) object);
        } else if (object instanceof SemanticParagraph) {
            writeParagraph((SemanticParagraph) object);
        } else if (object instanceof SemanticTextNode) {
            writeSemanticTextNode((SemanticTextNode) object);
        } else if (object instanceof TableBorder) {
            writeTable((TableBorder) object);
        } else if (object instanceof PDFList) {
            writeList((PDFList) object);
        } else {
            return;
        }

        if (!isInsideTable()) {
            htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
        }
    }

    /**
     * Writes a header or footer element to the HTML output.
     *
     * @param headerOrFooter the header or footer to write
     * @throws IOException if unable to write to the output
     */
    protected void writeHeaderOrFooter(SemanticHeaderOrFooter headerOrFooter) throws IOException {
        for (IObject content : headerOrFooter.getContents()) {
            write(content);
        }
    }

    /**
     * Writes a formula element to the HTML output using MathJax-compatible markup.
     *
     * @param formula the formula to write
     * @throws IOException if unable to write to the output
     */
    protected void writeFormula(SemanticFormula formula) throws IOException {
        String style = HtmlSyntax.HTML_POSITION_ABSOLUTE_PROPERTY + getObjectStyle(formula);
        String styleAttribute = String.format(HtmlSyntax.HTML_STYLE_ATTRIBUTE, style.trim());
        htmlWriter.write(String.format(HtmlSyntax.HTML_MATH_DISPLAY_TAG, styleAttribute));
        htmlWriter.write("\\[");
        htmlWriter.write(getCorrectString(formula.getLatex()));
        htmlWriter.write("\\]");
        htmlWriter.write(HtmlSyntax.HTML_MATH_DISPLAY_CLOSE_TAG);
        htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
    }

    /**
     * Writes an image element to the HTML output.
     *
     * @param image the image chunk to write
     */
    protected void writeImage(ImageChunk image) {
        try {
            String absolutePath = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT, StaticLayoutContainers.getImagesDirectory(), File.separator, image.getIndex(), imageFormat);
            String relativePath = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT, StaticLayoutContainers.getImagesDirectoryName(), "/", image.getIndex(), imageFormat);

            if (ImagesUtils.isImageFileExists(absolutePath)) {
                String imageSource;
                if (embedImages) {
                    File imageFile = new File(absolutePath);
                    imageSource = Base64ImageUtils.toDataUri(imageFile, imageFormat);
                    if (imageSource == null) {
                        LOGGER.log(Level.WARNING, "Failed to convert image to Base64: {0}", absolutePath);
                    }
                } else {
                    imageSource = relativePath;
                }
                if (imageSource != null) {
                    String escapedSource = escapeHtmlAttribute(imageSource);
                    // Empty alt is correct HTML for "missing description": screen
                    // readers skip it, and our evidence-report flags it as
                    // alt_source="missing". Never synthesize "figureN".
                    String altText = (image instanceof EnrichedImageChunk && ((EnrichedImageChunk) image).hasDescription())
                            ? ((EnrichedImageChunk) image).sanitizeDescription()
                            : "";
                    String styleAttribute = String.format(HtmlSyntax.HTML_STYLE_ATTRIBUTE, getObjectStyle(image).trim());
                    String imageString = String.format("<img src=\"%s\" alt=\"%s\"%s>", escapedSource, altText, styleAttribute);
                    htmlWriter.write(imageString);
                    htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to write image for html output: " + e.getMessage());
        }
    }

    /**
     * Writes a SemanticPicture element with figure/figcaption for description.
     *
     * @param picture the picture to write
     */
    protected void writePicture(SemanticPicture picture) {
        try {
            String absolutePath = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT, StaticLayoutContainers.getImagesDirectory(), File.separator, picture.getPictureIndex(), imageFormat);
            String relativePath = String.format(MarkdownSyntax.IMAGE_FILE_NAME_FORMAT, StaticLayoutContainers.getImagesDirectoryName(), "/", picture.getPictureIndex(), imageFormat);

            if (ImagesUtils.isImageFileExists(absolutePath)) {
                String imageSource;
                if (embedImages) {
                    File imageFile = new File(absolutePath);
                    imageSource = Base64ImageUtils.toDataUri(imageFile, imageFormat);
                    if (imageSource == null) {
                        LOGGER.log(Level.WARNING, "Failed to convert image to Base64: {0}", absolutePath);
                    }
                } else {
                    imageSource = relativePath;
                }
                if (imageSource != null) {
                    String altText = picture.hasDescription()
                            ? picture.sanitizeDescription()
                            : "";
                    String escapedSource = escapeHtmlAttribute(imageSource);
                    String styleAttribute =  String.format(HtmlSyntax.HTML_STYLE_ATTRIBUTE, getObjectStyle(picture).trim());
                    htmlWriter.write(String.format(HtmlSyntax.HTML_FIGURE_TAG, styleAttribute));
                    htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
                    String imageString = String.format("<img src=\"%s\" alt=\"%s\">", escapedSource, altText);
                    htmlWriter.write(imageString);
                    htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
                    htmlWriter.write(HtmlSyntax.HTML_FIGURE_CLOSE_TAG);
                    htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Unable to write picture for html output: " + e.getMessage());
        }
    }

    /**
     * Writes a list element to the HTML output.
     *
     * @param list the PDF list to write
     * @throws IOException if unable to write to the output
     */
    protected void writeList(PDFList list) throws IOException {
        String styleAttribute = String.format(HtmlSyntax.HTML_STYLE_ATTRIBUTE, getPositionProperty(list).trim());
        htmlWriter.write(String.format(HtmlSyntax.HTML_UNORDERED_LIST_TAG, styleAttribute));
        htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
        nestedObjects.add(list);
        double nestedListHeight = 0.0;
        for (int i = 0; i < list.getListItems().size(); i++) {
            ListItem item = list.getListItems().get(i);
            nestedObjects.add(item);
            String itemStyle = nestedListHeight != 0.0 ?
                String.format(HtmlSyntax.HTML_TOP_PROPERTY, convertPtToPx(nestedListHeight)) : "";
            String itemStyleAttribute = itemStyle.isEmpty() ? "" : String.format(HtmlSyntax.HTML_STYLE_ATTRIBUTE, itemStyle.trim());
            htmlWriter.write(String.format(HtmlSyntax.HTML_LIST_ITEM_TAG, itemStyleAttribute));
            String pStyle = String.format(HtmlSyntax.HTML_STYLE_ATTRIBUTE, String.format(HtmlSyntax.HTML_WIDTH_PROPERTY, convertPtToPx(item.getWidth())));
            htmlWriter.write(String.format(HtmlSyntax.HTML_PARAGRAPH_TAG, pStyle));
            String value = GeneratorUtils.getTextFromLines(item.getLines(), OutputType.HTML);
            htmlWriter.write(getCorrectString(value));
            htmlWriter.write(HtmlSyntax.HTML_PARAGRAPH_CLOSE_TAG);
            List<IObject> contents = item.getContents();
            for (IObject object : contents) {
                write(object);
            }
            if (!contents.isEmpty()) {
                nestedListHeight += nestedObjects.peek().getTopY() - contents.get(contents.size() - 1).getBottomY() + 5;
            } else {
                if (i < list.getListItems().size() - 1) {
                    nestedListHeight += item.getTopY() - list.getListItems().get(i + 1).getTopY();
                }
            }
            htmlWriter.write(HtmlSyntax.HTML_LIST_ITEM_CLOSE_TAG);
            htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
            nestedObjects.pop();
        }
        htmlWriter.write(HtmlSyntax.HTML_UNORDERED_LIST_CLOSE_TAG);
        htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
        nestedObjects.pop();
    }

    /**
     * Writes a semantic text node as a figure caption to the HTML output.
     *
     * @param textNode the text node to write
     * @throws IOException if unable to write to the output
     */
    protected void writeSemanticTextNode(SemanticTextNode textNode) throws IOException {
        String styleAttribute = String.format(HtmlSyntax.HTML_STYLE_ATTRIBUTE, getTextNodeStyle(textNode).trim());
        htmlWriter.write(String.format(HtmlSyntax.HTML_FIGURE_CAPTION_TAG,  styleAttribute));
        htmlWriter.write(getCorrectString(GeneratorUtils.getTextFromTextNode(textNode, OutputType.HTML)));
        htmlWriter.write(HtmlSyntax.HTML_FIGURE_CAPTION_CLOSE_TAG);
        htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
    }

    /**
     * Writes a table element to the HTML output.
     *
     * @param table the table border to write
     * @throws IOException if unable to write to the output
     */
    protected void writeTable(TableBorder table) throws IOException {
        String style = getObjectStyle(table) + String.format(HtmlSyntax.HTML_HEIGHT_PROPERTY, convertPtToPx(table.getHeight()));
        nestedObjects.add(table);
        enterTable();
        String styleAttribute = String.format(HtmlSyntax.HTML_STYLE_ATTRIBUTE, style.trim());
        htmlWriter.write(String.format(HtmlSyntax.HTML_TABLE_TAG, styleAttribute));
        htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
        for (int rowNumber = 0; rowNumber < table.getNumberOfRows(); rowNumber++) {
            TableBorderRow row = table.getRow(rowNumber);
            String rowStyle = String.format(HtmlSyntax.HTML_STYLE_ATTRIBUTE, String.format(HtmlSyntax.HTML_HEIGHT_PROPERTY, convertPtToPx(row.getHeight())).trim());
            htmlWriter.write(String.format(HtmlSyntax.HTML_TABLE_ROW_TAG, rowStyle));
            htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
            for (int colNumber = 0; colNumber < table.getNumberOfColumns(); colNumber++) {
                TableBorderCell cell = row.getCell(colNumber);
                if (cell.getRowNumber() == rowNumber && cell.getColNumber() == colNumber) {
                    boolean isHeader = rowNumber == 0;
                    writeCellTag(cell, isHeader);
                    List<IObject> contents = cell.getContents();
                    if (!contents.isEmpty()) {
                        for (IObject contentItem : contents) {
                            this.write(contentItem);
                        }
                    }
                    if (isHeader) {
                        htmlWriter.write(HtmlSyntax.HTML_TABLE_HEADER_CLOSE_TAG);
                    } else {
                        htmlWriter.write(HtmlSyntax.HTML_TABLE_CELL_CLOSE_TAG);
                    }
                    htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
                }
            }

            htmlWriter.write(HtmlSyntax.HTML_TABLE_ROW_CLOSE_TAG);
            htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
        }

        htmlWriter.write(HtmlSyntax.HTML_TABLE_CLOSE_TAG);
        htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
        leaveTable();
        nestedObjects.pop();
    }

    /**
     * Writes a paragraph element to the HTML output.
     *
     * @param paragraph the semantic paragraph to write
     * @throws IOException if unable to write to the output
     */
    protected void writeParagraph(SemanticParagraph paragraph) throws IOException {
        double paragraphIndent = paragraph.getColumns().get(0).getBlocks().get(0).getFirstLineIndent();
        String styleAttribute = String.format(HtmlSyntax.HTML_STYLE_ATTRIBUTE, getTextNodeStyle(paragraph).trim());
        htmlWriter.write(String.format(HtmlSyntax.HTML_PARAGRAPH_TAG, styleAttribute));
        if (paragraphIndent > 0) {
            htmlWriter.write(HtmlSyntax.HTML_INDENT);
        }
        String paragraphValue = GeneratorUtils.getTextFromTextNode(paragraph, OutputType.HTML);

        if (isInsideTable() && StaticContainers.isKeepLineBreaks()) {
            paragraphValue = paragraphValue.replace(HtmlSyntax.HTML_LINE_BREAK, HtmlSyntax.HTML_LINE_BREAK_TAG);
        }

        htmlWriter.write(getCorrectString(paragraphValue));
        htmlWriter.write(HtmlSyntax.HTML_PARAGRAPH_CLOSE_TAG);
        htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
    }

    /**
     * Writes a heading element to the HTML output.
     *
     * @param heading the semantic heading to write
     * @throws IOException if unable to write to the output
     */
    protected void writeHeading(SemanticHeading heading) throws IOException {
        isHeading = true;
        int headingLevel = Math.min(6, Math.max(1, heading.getHeadingLevel()));
        String styleAttribute = String.format(HtmlSyntax.HTML_STYLE_ATTRIBUTE, getTextNodeStyle(heading).trim());
        htmlWriter.write("<h" + headingLevel + styleAttribute + ">");
        htmlWriter.write(getCorrectString(GeneratorUtils.getTextFromTextNode(heading, OutputType.HTML)));
        htmlWriter.write("</h" + headingLevel + ">");
        htmlWriter.write(HtmlSyntax.HTML_LINE_BREAK);
        isHeading = false;
    }

    private void writeCellTag(TableBorderCell cell, boolean isHeader) throws IOException {
        String tag = isHeader ? "<th" : "<td";
        StringBuilder cellTag = new StringBuilder(tag);
        int colSpan = cell.getColSpan();
        if (colSpan != 1) {
            cellTag.append(" colspan=\"").append(colSpan).append("\"");
        }

        int rowSpan = cell.getRowSpan();
        if (rowSpan != 1) {
            cellTag.append(" rowspan=\"").append(rowSpan).append("\"");
        }
        String style = String.format(HtmlSyntax.HTML_STYLE_ATTRIBUTE, String.format(HtmlSyntax.HTML_WIDTH_PROPERTY, convertPtToPx(cell.getWidth())).trim());
        cellTag.append(style);
        cellTag.append(">");
        htmlWriter.write(getCorrectString(cellTag.toString()));
    }

    /**
     * Increments the table nesting level when entering a table.
     */
    protected void enterTable() {
        tableNesting++;
    }

    /**
     * Decrements the table nesting level when leaving a table.
     */
    protected void leaveTable() {
        if (tableNesting > 0) {
            tableNesting--;
        }
    }

    /**
     * Checks whether currently writing inside a table.
     *
     * @return true if inside a table, false otherwise
     */
    protected boolean isInsideTable() {
        return tableNesting > 0;
    }

    /**
     * Removes null characters from the given string.
     *
     * @param value the string to process
     * @return the string with null characters removed, or null if input is null
     */
    protected String getCorrectString(String value) {
        if (value != null) {
            return value.replace("\u0000", "");
        }
        return null;
    }

    /**
     * Escapes special characters for use in HTML attributes.
     * Handles quotes, ampersands, less-than, greater-than, and newlines.
     *
     * @param value the string to escape
     * @return the escaped string safe for HTML attribute values
     */
    protected static String escapeHtmlAttribute(String value) {
        if (value == null) {
            return null;
        }
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", " ")
            .replace("\r", "");
    }

    public static void getTextFromLineForHTML(TextLine line, StringBuilder stringBuilder) {
        for (TextChunk chunk : line.getTextChunks()) {
            String style = getTextStyle(chunk);
            if (!style.isEmpty()) {
                String styleAttribute = String.format(HtmlSyntax.HTML_STYLE_ATTRIBUTE, style.trim());
                stringBuilder.append(String.format(HtmlSyntax.HTML_SPAN_START_TAG, styleAttribute));
                stringBuilder.append(escapeHtmlAttribute(chunk.getValue()));
                stringBuilder.append(HtmlSyntax.HTML_SPAN_CLOSE_TAG);
            } else {
                stringBuilder.append(escapeHtmlAttribute(chunk.getValue()));
            }
        }
    }

    public String getTextNodeStyle(SemanticTextNode node) {
        return getPositionProperty(node) + getTextAlignmentProperty(node) + String.format(HtmlSyntax.HTML_WIDTH_PROPERTY, Math.ceil(convertPtToPx(node.getWidth())));
    }

    public String getObjectStyle(BaseObject object) {
        return getPositionProperty(object) + String.format(HtmlSyntax.HTML_WIDTH_PROPERTY, Math.ceil(convertPtToPx(object.getWidth())));
    }

    private static String getTextStyle(TextChunk chunk) {
        StringBuilder style = new StringBuilder();
        if (chunk.getIsStrikethroughText()) {
            style.append(HtmlSyntax.HTML_STRIKETHROUGH_STYLE_PROPERTY);
        }
        if (chunk.isItalic()) {
            style.append(HtmlSyntax.HTML_ITALIC_STYLE_PROPERTY);
        }
        double fontSizeInPx = convertPtToPx(chunk.getFontSize());
        if (!NodeUtils.areCloseNumbers(fontSizeInPx, 16.0) || isHeading) {
            style.append(String.format(HtmlSyntax.HTML_FONT_SIZE_PROPERTY, fontSizeInPx));
        }
        Color color = chunk.getTextColor();
        if (color != null && (color.getRGB() & 0x00FFFFFF) != (Color.BLACK.getRGB() & 0x00FFFFFF)) {
            style.append(String.format(HtmlSyntax.HTML_FONT_COLOR_PROPERTY,
                color.getRed(), color.getGreen(), color.getBlue()));
        }
        int fontWeight = chunk.getRoundedFontWeight();
        if (fontWeight != 400) {
            style.append(String.format(HtmlSyntax.HTML_FONT_WEIGHT_PROPERTY, fontWeight));
        }
        return style.toString();
    }

    public static double convertPtToPx(double sizeInPt) {
        return sizeInPt * 4.0 / 3.0;
    }

    private String getPositionProperty(BaseObject object) {
        return String.format(HtmlSyntax.HTML_TOP_PROPERTY, convertPtToPx(nestedObjects.isEmpty() ?
            pagesHeight - object.getTopY() :
            (nestedObjects.peek().getTopY() - object.getTopY()))) +
            String.format(HtmlSyntax.HTML_LEFT_PROPERTY, convertPtToPx(nestedObjects.isEmpty()
                ? object.getLeftX() : (object.getLeftX() - nestedObjects.peek().getLeftX())));
    }

    private String getTextAlignmentProperty(SemanticTextNode node) {
        TextAlignment textAlignment = node.getColumns().get(0).getBlocks().get(0).getTextAlignment();
        if (textAlignment == null || textAlignment == TextAlignment.LEFT) {
            return "";
        }
        return String.format(HtmlSyntax.HTML_TEXT_ALIGNMENT_PROPERTY, textAlignment.name().toLowerCase());
    }

    @Override
    public void close() throws IOException {
        if (htmlWriter != null) {
            htmlWriter.close();
        }
    }
}
