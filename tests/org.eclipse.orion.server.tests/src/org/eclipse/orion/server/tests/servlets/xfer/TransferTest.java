/*******************************************************************************
 * Copyright (c) 2011, 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.tests.servlets.xfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.URIUtil;
import org.eclipse.orion.internal.server.core.IOUtilities;
import org.eclipse.orion.internal.server.servlets.ProtocolConstants;
import org.eclipse.orion.server.tests.ServerTestsActivator;
import org.eclipse.orion.server.tests.servlets.files.FileSystemTest;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.xml.sax.SAXException;

import com.meterware.httpunit.GetMethodWebRequest;
import com.meterware.httpunit.PostMethodWebRequest;
import com.meterware.httpunit.PutMethodWebRequest;
import com.meterware.httpunit.WebConversation;
import com.meterware.httpunit.WebResponse;

/**
 * 
 */
public class TransferTest extends FileSystemTest {
	@BeforeClass
	public static void setupWorkspace() {
		initializeWorkspaceLocation();
	}

	private void doImport(File source, long length, String location) throws FileNotFoundException, IOException, SAXException {
		//repeat putting chunks until done
		byte[] chunk = new byte[64 * 1024];
		InputStream in = new BufferedInputStream(new FileInputStream(source));
		int chunkSize = 0;
		int totalTransferred = 0;
		while ((chunkSize = in.read(chunk, 0, chunk.length)) > 0) {
			PutMethodWebRequest put = new PutMethodWebRequest(location, new ByteArrayInputStream(chunk, 0, chunkSize), "application/zip");
			put.setHeaderField("Content-Range", "bytes " + totalTransferred + "-" + (totalTransferred + chunkSize - 1) + "/" + length);
			put.setHeaderField("Content-Length", "" + length);
			put.setHeaderField("Content-Type", "application/zip");
			setAuthentication(put);
			totalTransferred += chunkSize;
			WebResponse putResponse = webConversation.getResponse(put);
			if (totalTransferred == length) {
				assertEquals(HttpURLConnection.HTTP_CREATED, putResponse.getResponseCode());
			} else {
				assertEquals(308, putResponse.getResponseCode());//308 = Permanent Redirect
				String range = putResponse.getHeaderField("Range");
				assertEquals("bytes 0-" + (totalTransferred - 1), range);
			}

		}
	}

	/**
	 * Returns the URI of an import HTTP request on the given directory.
	 */
	private String getImportRequestPath(String directoryPath) {
		IPath path = new Path("/xfer/import").append(getTestBaseResourceURILocation()).append(directoryPath);
		return ServerTestsActivator.getServerLocation() + path.toString();
	}

	/**
	 * Returns the URI of an export HTTP request on the given directory.
	 */
	private String getExportRequestPath(String directoryPath) {
		IPath path = new Path("/xfer/export").append(getTestBaseResourceURILocation()).append(directoryPath).removeTrailingSeparator().addFileExtension("zip");
		return ServerTestsActivator.getServerLocation() + path.toString();
	}

	@After
	public void removeTempDir() throws CoreException {
		remove("sample");
	}

	@Before
	public void setUp() throws Exception {
		webConversation = new WebConversation();
		webConversation.setExceptionsThrownOnErrorStatus(false);
		setUpAuthorization();
		createTestProject("TransferTest");
	}

	@Test
	public void testExportProject() throws CoreException, IOException, SAXException {
		//create content to export
		String directoryPath = "sample/directory/path" + System.currentTimeMillis();
		createDirectory(directoryPath);
		String fileContents = "This is the file contents";
		createFile(directoryPath + "/file.txt", fileContents);

		GetMethodWebRequest export = new GetMethodWebRequest(getExportRequestPath(directoryPath));
		setAuthentication(export);
		WebResponse response = webConversation.getResponse(export);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());
		boolean found = false;
		ZipInputStream in = new ZipInputStream(response.getInputStream());
		ZipEntry entry;
		while ((entry = in.getNextEntry()) != null) {
			if (entry.getName().equals("file.txt")) {
				found = true;
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				IOUtilities.pipe(in, bytes, false, false);
				assertEquals(fileContents, new String(bytes.toByteArray()));
			}
		}
		assertTrue(found);
	}

	/**
	 * Tests importing a zip file from a remote URL, and verifying that it is imported
	 */
	@Test
	public void testImportFromURL() throws CoreException, IOException, SAXException {
		//just a known zip file that we can use for testing that is stable
		String sourceZip = "http://eclipse.org/eclipse/platform-core/downloads/tools/org.eclipse.core.tools.restorer_3.0.0.zip";

		//create a directory to upload to
		String directoryPath = "sample/directory/path" + System.currentTimeMillis();
		createDirectory(directoryPath);

		//start the import
		String requestPath = getImportRequestPath(directoryPath) + "?source=" + sourceZip;
		PostMethodWebRequest request = new PostMethodWebRequest(requestPath);
		setAuthentication(request);
		request.setHeaderField(ProtocolConstants.HEADER_XFER_OPTIONS, "raw");
		WebResponse postResponse = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_CREATED, postResponse.getResponseCode());
		String location = postResponse.getHeaderField("Location");
		assertNotNull(location);

		//assert the file has been imported but not unzipped
		assertTrue(checkFileExists(directoryPath + "/org.eclipse.core.tools.restorer_3.0.0.zip"));
	}

	/**
	 * Tests importing a zip file from a remote URL, and verifying that it is imported and unzipped.
	 */
	@Test
	public void testImportAndUnzipFromURL() throws CoreException, IOException, SAXException {
		//just a known zip file that we can use for testing that is stable
		String sourceZip = "http://eclipse.org/eclipse/platform-core/downloads/tools/org.eclipse.core.tools.restorer_3.0.0.zip";

		//create a directory to upload to
		String directoryPath = "sample/directory/path" + System.currentTimeMillis();
		createDirectory(directoryPath);

		//start the import
		String requestPath = getImportRequestPath(directoryPath) + "?source=" + sourceZip;
		PostMethodWebRequest request = new PostMethodWebRequest(requestPath);
		setAuthentication(request);
		WebResponse postResponse = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_CREATED, postResponse.getResponseCode());
		String location = postResponse.getHeaderField("Location");
		assertNotNull(location);

		//assert the file has been unzipped in the workspace
		assertTrue(checkFileExists(directoryPath + "/org.eclipse.core.tools.restorer_3.0.0/org.eclipse.core.tools.restorer_3.0.0.200607121527.jar"));
	}

	/**
	 * Tests importing from a URL, where the source URL is not absolute. This should fail.
	 */
	@Test
	public void testImportFromURLMalformed() throws CoreException, IOException, SAXException {
		//create a directory to upload to
		String directoryPath = "sample/directory/path" + System.currentTimeMillis();
		createDirectory(directoryPath);

		//start the import
		String requestPath = getImportRequestPath(directoryPath) + "?source=pumpkins";
		PostMethodWebRequest request = new PostMethodWebRequest(requestPath);
		setAuthentication(request);
		WebResponse postResponse = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, postResponse.getResponseCode());
	}

	@Test
	public void testImportAndUnzip() throws CoreException, IOException, SAXException, URISyntaxException {
		//create a directory to upload to
		String directoryPath = "sample/directory/path" + System.currentTimeMillis();
		createDirectory(directoryPath);

		//start the import
		URL entry = ServerTestsActivator.getContext().getBundle().getEntry("testData/importTest/client.zip");
		File source = new File(FileLocator.toFileURL(entry).getPath());
		long length = source.length();
		String importPath = getImportRequestPath(directoryPath);
		PostMethodWebRequest request = new PostMethodWebRequest(importPath);
		request.setHeaderField("X-Xfer-Content-Length", Long.toString(length));
		setAuthentication(request);
		WebResponse postResponse = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, postResponse.getResponseCode());
		String location = postResponse.getHeaderField("Location");
		assertNotNull(location);
		URI importURI = URIUtil.fromString(importPath);
		location = importURI.resolve(location).toString();
		doImport(source, length, location);

		//assert the file has been unzipped in the workspace
		assertTrue(checkFileExists(directoryPath + "/org.eclipse.e4.webide/static/js/navigate-tree/navigate-tree.js"));
	}

	@Test
	public void testImportFile() throws CoreException, IOException, SAXException, URISyntaxException {
		//create a directory to upload to
		String directoryPath = "sample/directory/path" + System.currentTimeMillis();
		createDirectory(directoryPath);

		//start the import
		URL entry = ServerTestsActivator.getContext().getBundle().getEntry("testData/importTest/client.zip");
		File source = new File(FileLocator.toFileURL(entry).getPath());
		long length = source.length();
		String importPath = getImportRequestPath(directoryPath);
		PostMethodWebRequest request = new PostMethodWebRequest(importPath);
		request.setHeaderField("X-Xfer-Content-Length", Long.toString(length));
		request.setHeaderField("X-Xfer-Options", "raw");

		request.setHeaderField("Slug", "client.zip");
		setAuthentication(request);
		WebResponse postResponse = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, postResponse.getResponseCode());
		String location = postResponse.getHeaderField("Location");
		assertNotNull(location);
		URI importURI = URIUtil.fromString(importPath);
		location = importURI.resolve(location).toString();

		doImport(source, length, location);

		//assert the file is present in the workspace
		assertTrue(checkFileExists(directoryPath + "/client.zip"));
	}

	/**
	 * Tests attempting to import and unzip a file that is not a zip file.
	 */
	@Test
	public void testImportUnzipNonZipFile() throws CoreException, IOException, SAXException {
		//create a directory to upload to
		String directoryPath = "sample/testImportWithPost/path" + System.currentTimeMillis();
		createDirectory(directoryPath);

		//start the import
		URL entry = ServerTestsActivator.getContext().getBundle().getEntry("testData/importTest/junk.zip");
		File source = new File(FileLocator.toFileURL(entry).getPath());
		long length = source.length();
		InputStream in = new BufferedInputStream(new FileInputStream(source));
		PostMethodWebRequest request = new PostMethodWebRequest(getImportRequestPath(directoryPath), in, "application/zip");
		request.setHeaderField("Content-Length", "" + length);
		request.setHeaderField("Content-Type", "application/zip");
		setAuthentication(request);
		WebResponse postResponse = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, postResponse.getResponseCode());
	}

	@Test
	public void testImportWithPost() throws CoreException, IOException, SAXException {
		//create a directory to upload to
		String directoryPath = "sample/testImportWithPost/path" + System.currentTimeMillis();
		createDirectory(directoryPath);

		//start the import
		URL entry = ServerTestsActivator.getContext().getBundle().getEntry("testData/importTest/client.zip");
		File source = new File(FileLocator.toFileURL(entry).getPath());
		long length = source.length();
		InputStream in = new BufferedInputStream(new FileInputStream(source));
		PostMethodWebRequest request = new PostMethodWebRequest(getImportRequestPath(directoryPath), in, "application/zip");
		request.setHeaderField("Content-Length", "" + length);
		request.setHeaderField("Content-Type", "application/zip");
		setAuthentication(request);
		WebResponse postResponse = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_CREATED, postResponse.getResponseCode());
		String location = postResponse.getHeaderField("Location");
		assertNotNull(location);
		String type = postResponse.getHeaderField("Content-Type");
		assertNotNull(type);
		assertTrue(type.contains("text/html"));

		//assert the file has been unzipped in the workspace
		assertTrue(checkFileExists(directoryPath + "/org.eclipse.e4.webide/static/js/navigate-tree/navigate-tree.js"));
	}
}
