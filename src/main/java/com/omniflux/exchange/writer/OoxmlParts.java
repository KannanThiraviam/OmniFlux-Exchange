package com.omniflux.exchange.writer;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * Writes the fixed package parts of the minimal XLSX workbook.
 * <p>
 * Every URI below is an XML namespace or relationship-type identifier whose
 * spelling is fixed by the OOXML spec (ECMA-376); none is ever fetched.
 */
@SuppressWarnings("HttpUrlsUsage")
final class OoxmlParts {

    static final String CONTENT_TYPES_NS = "http://schemas.openxmlformats.org/package/2006/content-types";
    static final String RELATIONSHIPS_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final String DOCUMENT_RELATIONSHIPS_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    static final String XML_NS = "http://www.w3.org/XML/1998/namespace";

    /** Every styles list element carries the OOXML {@code count} attribute. */
    static final String COUNT_ATTR = "count";

    static final String RELATIONSHIPS_CONTENT_TYPE =
            "application/vnd.openxmlformats-package.relationships+xml";

    private OoxmlParts() { }

    static void contentTypes(XMLStreamWriter xml) throws XMLStreamException {
        startDocument(xml);
        xml.writeStartElement("Types");
        xml.writeDefaultNamespace(CONTENT_TYPES_NS);

        defaultType(xml, "rels", RELATIONSHIPS_CONTENT_TYPE);
        defaultType(xml, "xml", "application/xml");
        overrideType(xml, "/_rels/.rels", RELATIONSHIPS_CONTENT_TYPE);
        overrideType(xml, "/xl/workbook.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml");
        overrideType(xml, "/xl/_rels/workbook.xml.rels", RELATIONSHIPS_CONTENT_TYPE);
        overrideType(xml, "/xl/worksheets/sheet1.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml");
        overrideType(xml, "/xl/styles.xml",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml");

        xml.writeEndElement();
        xml.writeEndDocument();
    }

    static void packageRelationships(XMLStreamWriter xml) throws XMLStreamException {
        startDocument(xml);
        xml.writeStartElement("Relationships");
        xml.writeDefaultNamespace(RELATIONSHIPS_NS);
        relationship(xml, "rId1",
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument",
                "xl/workbook.xml");
        xml.writeEndElement();
        xml.writeEndDocument();
    }

    static void workbook(XMLStreamWriter xml) throws XMLStreamException {
        startDocument(xml);
        xml.writeStartElement("workbook");
        xml.writeDefaultNamespace(MAIN_NS);
        xml.writeNamespace("r", DOCUMENT_RELATIONSHIPS_NS);
        xml.writeStartElement("sheets");
        xml.writeStartElement("sheet");
        xml.writeAttribute("name", "Sheet1");
        xml.writeAttribute("sheetId", "1");
        xml.writeAttribute("r", DOCUMENT_RELATIONSHIPS_NS, "id", "rId1");
        xml.writeEndElement();
        xml.writeEndElement();
        xml.writeEndElement();
        xml.writeEndDocument();
    }

    static void workbookRelationships(XMLStreamWriter xml) throws XMLStreamException {
        startDocument(xml);
        xml.writeStartElement("Relationships");
        xml.writeDefaultNamespace(RELATIONSHIPS_NS);
        relationship(xml, "rId1",
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet",
                "worksheets/sheet1.xml");
        relationship(xml, "rId2",
                "http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles",
                "styles.xml");
        xml.writeEndElement();
        xml.writeEndDocument();
    }

    static void styles(XMLStreamWriter xml) throws XMLStreamException {
        startDocument(xml);
        xml.writeStartElement("styleSheet");
        xml.writeDefaultNamespace(MAIN_NS);

        xml.writeStartElement("numFmts");
        xml.writeAttribute(COUNT_ATTR, "0");
        xml.writeEndElement();

        xml.writeStartElement("fonts");
        xml.writeAttribute(COUNT_ATTR, "1");
        xml.writeStartElement("font");
        emptyAttributeElement(xml, "sz", "val", "11");
        emptyAttributeElement(xml, "color", "theme", "1");
        emptyAttributeElement(xml, "name", "val", "Calibri");
        emptyAttributeElement(xml, "family", "val", "2");
        emptyAttributeElement(xml, "scheme", "val", "minor");
        xml.writeEndElement();
        xml.writeEndElement();

        xml.writeStartElement("fills");
        xml.writeAttribute(COUNT_ATTR, "2");
        xml.writeStartElement("fill");
        emptyAttributeElement(xml, "patternFill", "patternType", "none");
        xml.writeEndElement();
        xml.writeStartElement("fill");
        emptyAttributeElement(xml, "patternFill", "patternType", "gray125");
        xml.writeEndElement();
        xml.writeEndElement();

        xml.writeStartElement("borders");
        xml.writeAttribute(COUNT_ATTR, "1");
        xml.writeStartElement("border");
        emptyElement(xml, "left");
        emptyElement(xml, "right");
        emptyElement(xml, "top");
        emptyElement(xml, "bottom");
        emptyElement(xml, "diagonal");
        xml.writeEndElement();
        xml.writeEndElement();

        xml.writeStartElement("cellStyleXfs");
        xml.writeAttribute(COUNT_ATTR, "1");
        xf(xml, true);
        xml.writeEndElement();

        xml.writeStartElement("cellXfs");
        xml.writeAttribute(COUNT_ATTR, "1");
        xf(xml, false);
        xml.writeEndElement();

        xml.writeStartElement("cellStyles");
        xml.writeAttribute(COUNT_ATTR, "1");
        xml.writeStartElement("cellStyle");
        xml.writeAttribute("name", "Normal");
        xml.writeAttribute("xfId", "0");
        xml.writeAttribute("builtinId", "0");
        xml.writeEndElement();
        xml.writeEndElement();

        xml.writeStartElement("dxfs");
        xml.writeAttribute(COUNT_ATTR, "0");
        xml.writeEndElement();

        xml.writeStartElement("tableStyles");
        xml.writeAttribute(COUNT_ATTR, "0");
        xml.writeAttribute("defaultTableStyle", "TableStyleMedium2");
        xml.writeAttribute("defaultPivotStyle", "PivotStyleLight16");
        xml.writeEndElement();

        xml.writeEndElement();
        xml.writeEndDocument();
    }

    private static void startDocument(XMLStreamWriter xml) throws XMLStreamException {
        xml.writeStartDocument("UTF-8", "1.0");
    }

    private static void defaultType(XMLStreamWriter xml, String extension, String contentType)
            throws XMLStreamException {
        xml.writeStartElement("Default");
        xml.writeAttribute("Extension", extension);
        xml.writeAttribute("ContentType", contentType);
        xml.writeEndElement();
    }

    private static void overrideType(XMLStreamWriter xml, String partName, String contentType)
            throws XMLStreamException {
        xml.writeStartElement("Override");
        xml.writeAttribute("PartName", partName);
        xml.writeAttribute("ContentType", contentType);
        xml.writeEndElement();
    }

    private static void relationship(XMLStreamWriter xml, String id, String type, String target)
            throws XMLStreamException {
        xml.writeStartElement("Relationship");
        xml.writeAttribute("Id", id);
        xml.writeAttribute("Type", type);
        xml.writeAttribute("Target", target);
        xml.writeEndElement();
    }

    private static void emptyAttributeElement(XMLStreamWriter xml, String name,
                                              String attribute, String value) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeAttribute(attribute, value);
        xml.writeEndElement();
    }

    private static void emptyElement(XMLStreamWriter xml, String name) throws XMLStreamException {
        xml.writeStartElement(name);
        xml.writeEndElement();
    }

    private static void xf(XMLStreamWriter xml, boolean styleBase) throws XMLStreamException {
        xml.writeStartElement("xf");
        xml.writeAttribute("numFmtId", "0");
        xml.writeAttribute("fontId", "0");
        xml.writeAttribute("fillId", "0");
        xml.writeAttribute("borderId", "0");
        if (!styleBase) xml.writeAttribute("xfId", "0");
        xml.writeEndElement();
    }
}
