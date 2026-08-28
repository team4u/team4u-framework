package org.team4u.release;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Deterministic, namespace-aware reader for the release structures checked by
 * check-release-contracts.sh. Java 8 compatible.
 */
public final class ReleasePomList {

    private ReleasePomList() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: ReleasePomList modules|artifacts|managed <pom>");
        }
        String mode = args[0];
        Document document = parse(args[1]);
        List<String> result;
        if ("modules".equals(mode)) {
            result = childTextValues(document.getDocumentElement(), "modules", "module");
        } else if ("artifacts".equals(mode)) {
            String artifactId = moduleArtifactId(document);
            if (artifactId == null) {
                throw new IllegalArgumentException("POM has no project artifactId: " + args[1]);
            }
            result = Collections.singletonList(artifactId);
        } else if ("managed".equals(mode)) {
            result = managedArtifactIds(document.getDocumentElement());
        } else {
            throw new IllegalArgumentException("unknown mode: " + mode);
        }
        Collections.sort(result);
        StringBuilder output = new StringBuilder();
        for (String value : result) {
            output.append(value).append('\n');
        }
        System.out.print(output);
    }

    private static Document parse(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File(path));
    }

    private static List<String> childTextValues(Element root, String containerTag, String childTag) {
        List<String> values = new ArrayList<String>();
        if (root == null) {
            return values;
        }
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element) || !matchesPomName((Element) child, containerTag)) {
                continue;
            }
            NodeList candidates = ((Element) child).getChildNodes();
            for (int j = 0; j < candidates.getLength(); j++) {
                Node candidate = candidates.item(j);
                if (candidate instanceof Element && matchesPomName((Element) candidate, childTag)) {
                    values.add(candidate.getTextContent().trim());
                }
            }
        }
        return values;
    }

    private static List<String> managedArtifactIds(Element project) {
        List<String> values = new ArrayList<String>();
        NodeList children = project.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element) || !matchesPomName((Element) child, "dependencyManagement")) {
                continue;
            }
            NodeList dependencies = ((Element) child).getChildNodes();
            for (int j = 0; j < dependencies.getLength(); j++) {
                Node dependency = dependencies.item(j);
                if (!(dependency instanceof Element) || !matchesPomName((Element) dependency, "dependencies")) {
                    continue;
                }
                NodeList entries = ((Element) dependency).getChildNodes();
                for (int k = 0; k < entries.getLength(); k++) {
                    Node entry = entries.item(k);
                    if (!(entry instanceof Element) || !matchesPomName((Element) entry, "dependency")) {
                        continue;
                    }
                    Element dependencyElement = (Element) entry;
                    String groupId = firstChildText(dependencyElement, "groupId");
                    if ("com.team4u".equals(groupId)) {
                        values.add(firstChildText(dependencyElement, "artifactId"));
                    }
                }
            }
        }
        return values;
    }
    private static String firstChildText(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && matchesPomName((Element) child, tag)) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }
    private static String moduleArtifactId(Document document) {
        Element project = document.getDocumentElement();
        NodeList children = project.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && matchesPomName((Element) child, "artifactId")) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    private static boolean matchesPomName(Element element, String localName) {
        return element.getLocalName().equals(localName)
                && ("http://maven.apache.org/POM/4.0.0".equals(element.getNamespaceURI())
                    || "http://maven.apache.org/xsd/maven-4.0.0.xsd".equals(element.getNamespaceURI())
                    || element.getNamespaceURI() == null);
    }
}
