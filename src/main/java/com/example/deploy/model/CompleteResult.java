package com.example.deploy.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Tab 自动补全结果：基于服务器终端当前目录（cwd）匹配到的候选路径。
 * 目录候选以 "/" 结尾，便于前端像 shell 一样识别并展示。
 */
public class CompleteResult {

    /** 补全匹配基于的远程目录（终端当前 cwd，可能为空表示登录默认目录） */
    private String cwd;
    /** 候选列表（目录带 / 后缀，目录在前、文件在后） */
    private List<String> candidates = new ArrayList<String>();

    public String getCwd() {
        return cwd;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public List<String> getCandidates() {
        return candidates;
    }

    public void setCandidates(List<String> candidates) {
        this.candidates = candidates;
    }
}
