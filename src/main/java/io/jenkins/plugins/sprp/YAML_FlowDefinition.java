/*
 * The MIT License
 *
 * Copyright 2018 Abhishek Gautam (@gautamabhishek46).
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package io.jenkins.plugins.sprp;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.ItemGroup;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import jenkins.branch.Branch;
import jenkins.scm.api.SCMFileSystem;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinitionDescriptor;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class YAML_FlowDefinition extends FlowDefinition {
    private String scriptPath;
    private GitConfig gitConfig;

    public Object readResolve() {
        if (this.scriptPath == null) {
            this.scriptPath = "Jenkinsfile.yaml";
        }
        return this;
    }

    public YAML_FlowDefinition(String scriptPath) {
        this.scriptPath = scriptPath;
    }

    @Override
    public FlowExecution create(FlowExecutionOwner owner, TaskListener listener,
                                          List<? extends Action> actions) throws Exception {
        Queue.Executable exec = owner.getExecutable();
        if (!(exec instanceof WorkflowRun)) {
            throw new IllegalStateException("inappropriate context");
        }

        WorkflowRun build = (WorkflowRun) exec;
        WorkflowJob job = build.getParent();
        BranchJobProperty property = job.getProperty(BranchJobProperty.class);

        Branch branch = property.getBranch();
        ItemGroup<?> parent = job.getParent();

        if (!(parent instanceof WorkflowMultiBranchProject)) {
            throw new IllegalStateException("inappropriate context");
        }

        SCMSource scmSource = ((WorkflowMultiBranchProject) parent).getSCMSource(branch.getSourceId());

        if (scmSource == null) {
            throw new IllegalStateException(branch.getSourceId() + " not found");
        }
        SCMHead head = branch.getHead();
        SCMRevision tip = scmSource.fetch(head, listener);
        if(tip == null)
            throw new IllegalStateException("Cannot determine the revision.");
        SCMRevision rev = scmSource.getTrustedRevision(tip, listener);
        GitSCM gitSCM = (GitSCM) scmSource.build(head, rev);

        this.gitConfig = new GitConfig();
        this.gitConfig.setGitBranch(property.getBranch().getName());

        if(gitConfig.getGitBranch().startsWith("PR-")){
            for(String urc: getCleanRefspecs(gitSCM.getUserRemoteConfigs())) {
                if(!urc.contains("PR-")) {
                    gitConfig.setGitBranch(getBranchName(urc.split("/")));
                    break;
                }
            }
        }

        this.gitConfig.setGitUrl(gitSCM.getUserRemoteConfigs().get(0).getUrl());
        this.gitConfig.setCredentialsId(gitSCM.getUserRemoteConfigs().get(0).getCredentialsId());

        String script;
        try (SCMFileSystem fs = SCMFileSystem.of(scmSource, head, rev)) {
            if (fs != null) {
                InputStream yamlInputStream = fs.child(scriptPath).content();
                listener.getLogger().println("Path of Jenkinsfile.yaml" + fs.child(scriptPath).getPath());
                YamlToPipeline y = new YamlToPipeline();
                script = y.generatePipeline(yamlInputStream, this.gitConfig, listener);
            } else {
                throw new IOException("SCM not supported");
                // FIXME implement full checkout
            }
        }

        listener.getLogger().println(script);
        return new CpsFlowExecution(script, false, owner);
    }

    // In refpecs there will be '+' symbol and some trailing white spaces, we need to remove them.
    private List<String> getCleanRefspecs(List<UserRemoteConfig> userRemoteConfigs){
        List<String> refSpecs = new ArrayList<>();

        for(UserRemoteConfig urc: userRemoteConfigs) {
            for (String singleRefSpec : urc.getRefspec().split("\\+", 0)) {
                if (!singleRefSpec.equals("")) {
                    refSpecs.add(singleRefSpec);
                }
            }
        }

        return refSpecs;
    }

    /*

    Bit-bucket generated following two similar refspecs:
    refs/heads/abhishekg1128/readmemd-edited-online-with-bitbucket-1529079381853:refs/remotes/origin/PR-1
    refs/heads/master:refs/remotes/upstream/master

    And GitHub generates following two similar refspecs in single string"
    +refs/pull/3/head:refs/remotes/origin/PR-3 +refs/heads/master:refs/remotes/origin/master

    */
    private String getBranchName(String[] refSpecsArray){
        boolean done = false;
        StringBuilder branchName = new StringBuilder();
        for(int i = 0; i < refSpecsArray.length && !done; i++){
            if(refSpecsArray[i].equals("upstream") || refSpecsArray[i].equals("origin")){
                for(int j = i + 1; j < refSpecsArray.length; j++) {
                    branchName.append(refSpecsArray[j]).append("/");
                }

                branchName = new StringBuilder(branchName.substring(0, branchName.length() - 1));
                done = true;
            }
        }

        return branchName.toString();
    }

    @Extension
    public static class DescriptorImpl extends FlowDefinitionDescriptor {

        @SuppressFBWarnings("NP_NONNULL_RETURN_VIOLATION")
        @Nonnull
        @Override public String getDisplayName() {
            return Messages.YAML_FlowDefinition_DescriptorImpl_DisplayName();
        }
    }
}
