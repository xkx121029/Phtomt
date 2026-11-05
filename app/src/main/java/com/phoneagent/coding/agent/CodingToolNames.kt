package com.phoneagent.coding.agent

/**
 * 编码工具的中文名：卡片标题、运行阶段、审批卡共用这一份口径。
 *
 * 工具名由 Node 侧按协议上报（read_file / run_command …），协议名不能改——
 * 改了宿主侧就认不出调用。中文只在展示层做一次映射，保证两处（卡片与阶段行）
 * 不会出现「执行 read_file」和「读取文件」同屏两种叫法。
 */
object CodingToolNames {

    private val labels = mapOf(
        "read_file" to "读取文件",
        "read_files" to "批量读取文件",
        "write_file" to "写入文件",
        "edit_file" to "编辑文件",
        "apply_patch" to "应用补丁",
        "list_dir" to "列出目录",
        "list_files" to "列出文件",
        "make_dir" to "新建目录",
        "delete_path" to "删除文件",
        "copy_path" to "复制文件",
        "tree" to "查看目录树",
        "glob" to "按文件名查找",
        "search_files" to "搜索文件",
        "grep" to "搜索内容",
        "todo_write" to "更新待办",
        "todo_read" to "读取待办",
        "run_command" to "执行命令",
        "bash" to "执行命令",
        "shell" to "执行命令",
        "web_fetch" to "抓取网页",
        "fetch" to "抓取网页",
        "git_read" to "读取 Git 信息",
        "git_write" to "写入 Git",
        "task" to "派发子任务",
        "env_status" to "查询编码环境",
        "env_setup" to "初始化编码环境",
        "env_build" to "构建项目",
        "env_cancel" to "取消构建",
        "workspace_tree" to "查看工作区目录",
        "workspace_read" to "读取工作区文件",
        "workspace_write" to "写入工作区文件",
        "workspace_create" to "新建工作区文件",
        "editor_open" to "打开编辑器",
        "preview_open" to "打开预览",
        "host_capabilities" to "读取宿主能力",
        "js_eval" to "运行脚本",
    )

    /** 协议名 -> 中文名；未知工具一律说「其他工具」，不把英文名漏进卡面 */
    fun label(raw: String?): String {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty()) return "工具"
        return labels[name] ?: "其他工具"
    }
}
