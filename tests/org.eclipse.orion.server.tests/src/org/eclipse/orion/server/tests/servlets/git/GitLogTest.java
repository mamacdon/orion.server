/*******************************************************************************
 * Copyright (c) 2011 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.tests.servlets.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.orion.internal.server.servlets.ProtocolConstants;
import org.eclipse.orion.server.git.GitConstants;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Ignore;
import org.junit.Test;

import com.meterware.httpunit.PostMethodWebRequest;
import com.meterware.httpunit.WebRequest;
import com.meterware.httpunit.WebResponse;

public class GitLogTest extends GitTest {

	@Test
	public void testLog() throws Exception {
		URI workspaceLocation = createWorkspace(getMethodName());

		String projectName = getMethodName();
		JSONObject project = createProjectOrLink(workspaceLocation, projectName, gitDir.toString());
		String projectId = project.getString(ProtocolConstants.KEY_ID);

		JSONObject gitSection = project.optJSONObject(GitConstants.KEY_GIT);
		assertNotNull(gitSection);
		String gitIndexUri = gitSection.getString(GitConstants.KEY_INDEX);
		String gitCommitUri = gitSection.getString(GitConstants.KEY_COMMIT);

		// modify
		WebRequest request = getPutFileRequest(projectId + "/test.txt", "first change");
		WebResponse response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());

		// TODO: don't create URIs out of thin air
		// add
		request = GitAddTest.getPutGitIndexRequest(gitIndexUri + "test.txt");
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());

		// commit1
		request = GitCommitTest.getPostGitCommitRequest(gitCommitUri, "commit1", false);
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());

		// modify again
		request = getPutFileRequest(projectId + "/test.txt", "second change");
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());

		// add
		request = GitAddTest.getPutGitIndexRequest(gitIndexUri + "test.txt");
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());

		// commit2
		request = GitCommitTest.getPostGitCommitRequest(gitCommitUri, "commit2", false);
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());

		// get the full log
		JSONArray commitsArray = log(gitCommitUri, false);
		assertEquals(3, commitsArray.length());

		JSONObject commit = commitsArray.getJSONObject(0);
		assertEquals("commit2", commit.get(GitConstants.KEY_COMMIT_MESSAGE));

		commit = commitsArray.getJSONObject(1);
		assertEquals("commit1", commit.get(GitConstants.KEY_COMMIT_MESSAGE));

		commit = commitsArray.getJSONObject(2);
		assertEquals("Initial commit", commit.get(GitConstants.KEY_COMMIT_MESSAGE));
		String initialGitCommitName = commit.getString(ProtocolConstants.KEY_NAME);
		String initialGitCommitURI = gitCommitUri.replaceAll("HEAD", initialGitCommitName);

		// prepare a scoped log location
		request = getPostForScopedLogRequest(initialGitCommitURI, "HEAD");
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());
		String newGitCommitUri = response.getHeaderField(ProtocolConstants.KEY_LOCATION);

		// get a scoped log
		commitsArray = log(newGitCommitUri, false);
		assertEquals(2, commitsArray.length());

		commit = commitsArray.getJSONObject(0);
		assertEquals("commit2", commit.get(GitConstants.KEY_COMMIT_MESSAGE));

		commit = commitsArray.getJSONObject(1);
		assertEquals("commit1", commit.get(GitConstants.KEY_COMMIT_MESSAGE));
	}

	@Test
	public void testLogWithRemote() throws Exception {
		// clone a repo
		URI workspaceLocation = createWorkspace(getMethodName());
		JSONObject project = createProjectOrLink(workspaceLocation, getMethodName(), null);
		IPath clonePath = new Path("file").append(project.getString(ProtocolConstants.KEY_ID)).makeAbsolute();
		clone(clonePath);

		// get project metadata
		WebRequest request = getGetFilesRequest(project.getString(ProtocolConstants.KEY_CONTENT_LOCATION));
		WebResponse response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());
		project = new JSONObject(response.getText());

		JSONObject gitSection = project.optJSONObject(GitConstants.KEY_GIT);
		assertNotNull(gitSection);
		String gitCommitUri = gitSection.getString(GitConstants.KEY_COMMIT);

		JSONArray commitsArray = log(gitCommitUri, true);
		assertEquals(1, commitsArray.length());
	}

	@Test
	public void testLogWithTag() throws Exception {
		// clone a repo
		URI workspaceLocation = createWorkspace(getMethodName());
		JSONObject project = createProjectOrLink(workspaceLocation, getMethodName(), null);
		IPath clonePath = new Path("file").append(project.getString(ProtocolConstants.KEY_ID)).makeAbsolute();
		clone(clonePath);

		// get project metadata
		WebRequest request = getGetFilesRequest(project.getString(ProtocolConstants.KEY_CONTENT_LOCATION));
		WebResponse response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());
		project = new JSONObject(response.getText());

		JSONObject gitSection = project.optJSONObject(GitConstants.KEY_GIT);
		assertNotNull(gitSection);
		String gitCommitUri = gitSection.getString(GitConstants.KEY_COMMIT);

		JSONArray commitsArray = log(gitCommitUri, true);
		assertEquals(1, commitsArray.length());

		String commitUri = commitsArray.getJSONObject(0).getString(ProtocolConstants.KEY_LOCATION);
		JSONObject updatedCommit = tag(commitUri, "tag");
		JSONArray tagsAndBranchesArray = updatedCommit.getJSONArray(ProtocolConstants.KEY_CHILDREN);
		assertEquals(1, tagsAndBranchesArray.length());
		assertEquals(Constants.R_TAGS + "tag", tagsAndBranchesArray.get(0));

		commitsArray = log(gitCommitUri, true);
		assertEquals(1, commitsArray.length());

		tagsAndBranchesArray = commitsArray.getJSONObject(0).getJSONArray(ProtocolConstants.KEY_CHILDREN);
		assertEquals(1, tagsAndBranchesArray.length());
		assertEquals(Constants.R_TAGS + "tag", tagsAndBranchesArray.get(0));
	}

	@Test
	@Ignore("bug 343644")
	public void testLogWithBranch() throws Exception {
		URI workspaceLocation = createWorkspace(getMethodName());

		JSONObject project = createProjectOrLink(workspaceLocation, getMethodName(), null);
		JSONObject clone = clone(new Path("file").append(project.getString(ProtocolConstants.KEY_ID)).makeAbsolute());
		String branchesLocation = clone.getString(GitConstants.KEY_BRANCH);

		// get project metadata
		WebRequest request = getGetFilesRequest(project.getString(ProtocolConstants.KEY_CONTENT_LOCATION));
		WebResponse response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());
		project = new JSONObject(response.getText());
		JSONObject gitSection = project.optJSONObject(GitConstants.KEY_GIT);
		String gitCommitUri = gitSection.getString(GitConstants.KEY_COMMIT);

		JSONArray commitsArray = log(gitCommitUri, true);
		assertEquals(1, commitsArray.length());

		branch(branchesLocation, "branch");

		commitsArray = log(gitCommitUri, true);
		assertEquals(1, commitsArray.length());

		JSONArray tagsAndBranchesArray = commitsArray.getJSONObject(0).getJSONArray(ProtocolConstants.KEY_CHILDREN);
		assertEquals(1, tagsAndBranchesArray.length());
		assertEquals(Constants.R_HEADS + "branch", tagsAndBranchesArray.get(0));
	}

	@Test
	public void testLogFile() throws Exception {
		URI workspaceLocation = createWorkspace(getMethodName());

		String projectName = getMethodName();
		JSONObject project = createProjectOrLink(workspaceLocation, projectName, gitDir.toString());
		String projectId = project.getString(ProtocolConstants.KEY_ID);

		JSONObject gitSection = project.optJSONObject(GitConstants.KEY_GIT);
		assertNotNull(gitSection);
		String gitIndexUri = gitSection.getString(GitConstants.KEY_INDEX);
		String gitCommitUri = gitSection.getString(GitConstants.KEY_COMMIT);

		// modify
		WebRequest request = getPutFileRequest(projectId + "/test.txt", "test.txt change");
		WebResponse response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());

		// TODO: don't create URIs out of thin air
		// add
		request = GitAddTest.getPutGitIndexRequest(gitIndexUri + "test.txt");
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());

		// commit1
		request = GitCommitTest.getPostGitCommitRequest(gitCommitUri, "commit1", false);
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());

		// modify again
		request = getPutFileRequest(projectId + "/folder/folder.txt", "folder.txt change");
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());

		// add
		request = GitAddTest.getPutGitIndexRequest(gitIndexUri + "folder/folder.txt");
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());

		// commit2
		request = GitCommitTest.getPostGitCommitRequest(gitCommitUri, "commit2", false);
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());

		// get log for file
		// TODO: don't create URIs out of thin air
		JSONArray commitsArray = log(gitCommitUri + "test.txt", false);
		assertEquals(2, commitsArray.length());

		JSONObject commit = commitsArray.getJSONObject(0);
		assertEquals("commit1", commit.get(GitConstants.KEY_COMMIT_MESSAGE));

		commit = commitsArray.getJSONObject(1);
		assertEquals("Initial commit", commit.get(GitConstants.KEY_COMMIT_MESSAGE));
	}

	@Test
	public void testLogNewBranch() throws Exception {
		URI workspaceLocation = createWorkspace(getMethodName());

		JSONObject project = createProjectOrLink(workspaceLocation, getMethodName(), null);
		JSONObject clone = clone(new Path("file").append(project.getString(ProtocolConstants.KEY_ID)).makeAbsolute());
		String cloneLocation = clone.getString(ProtocolConstants.KEY_LOCATION);
		String branchesLocation = clone.getString(GitConstants.KEY_BRANCH);

		// get project metadata
		WebRequest request = getGetFilesRequest(project.getString(ProtocolConstants.KEY_CONTENT_LOCATION));
		WebResponse response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());
		project = new JSONObject(response.getText());
		JSONObject gitSection = project.optJSONObject(GitConstants.KEY_GIT);
		String gitCommitUri = gitSection.getString(GitConstants.KEY_COMMIT);

		JSONArray commitsArray = log(gitCommitUri, true);
		assertEquals(1, commitsArray.length());

		branch(branchesLocation, "a");
		checkoutBranch(cloneLocation, "a");

		// get project metadata again
		request = getGetFilesRequest(project.getString(ProtocolConstants.KEY_LOCATION));
		response = webConversation.getResponse(request);
		assertEquals(HttpURLConnection.HTTP_OK, response.getResponseCode());
		project = new JSONObject(response.getText());
		gitSection = project.optJSONObject(GitConstants.KEY_GIT);
		gitCommitUri = gitSection.getString(GitConstants.KEY_COMMIT);

		log(gitCommitUri, true /* RemoteLocation should be available */);
	}

	private static WebRequest getPostForScopedLogRequest(String location, String newCommit) throws JSONException, UnsupportedEncodingException {
		String requestURI;
		if (location.startsWith("http://"))
			requestURI = location;
		else
			requestURI = SERVER_LOCATION + GIT_SERVLET_LOCATION + GitConstants.COMMIT_RESOURCE + location;

		JSONObject body = new JSONObject();
		body.put(GitConstants.KEY_COMMIT_NEW, newCommit);
		WebRequest request = new PostMethodWebRequest(requestURI, getJsonAsStream(body.toString()), "UTF-8");
		request.setHeaderField(ProtocolConstants.HEADER_ORION_VERSION, "1");
		setAuthentication(request);
		return request;
	}
}