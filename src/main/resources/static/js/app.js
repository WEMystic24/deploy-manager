(function () {
  'use strict';

  var servers = [];
  var current = null;
  var targets = [];
  var groups = [];               // 服务器文件夹（分组）列表，有序
  var expandedFolders = {};      // 文件夹名 -> true 表示已展开；默认全部折叠（无记录 = 折叠）
  var dragServerId = null;       // 当前正在拖拽的服务器 id（HTML5 拖拽）
  var dragTargetId = null;       // 当前正在拖拽的发布配置 id（HTML5 拖拽）
  var selectedTargetIds = {};    // 勾选（待顺序发布 / 批量重启）的发布配置 id -> true
  var selectedTargetMap = {};    // 勾选的发布配置详情 targetId -> target（含 serverId），支持跨服务器勾选后并行发布
  var serverDeployState = {};    // 每台服务器发布进度 serverId -> {taskId,taskName,steps,status,detail,pct,finishCount,total,...}
  var serverTargetCache = {};    // 各服务器发布配置缓存 serverId -> targets[]，用于取跨服务器勾选的 target 详情
  var sequentialDeploying = false; // 是否正在执行「并行一键发布」任务
  var batchRestarting = false;   // 是否正在执行「批量重启」任务
  var currentTarget = null;
  var autoLogTimer = null;
  var sftpCurrentPath = '';

  // ------------------------------------------------------------ 基础工具

  function $(id) {
    return document.getElementById(id);
  }

  function toast(msg, type) {
    var box = $('toast');
    var item = document.createElement('div');
    item.className = 'toast-item ' + (type || '');
    item.textContent = msg;
    box.appendChild(item);
    setTimeout(function () {
      box.removeChild(item);
    }, type === 'err' ? 5000 : 2600);
  }

  function request(url, options) {
    var opt = options || {};
    return fetch(url, opt).then(function (res) {
      if (res.status === 401) {
        throw new Error('未登录或认证失败');
      }
      return res.json();
    }).then(function (json) {
      if (json.code !== 0) {
        throw new Error(json.message || '请求失败');
      }
      return json.data;
    });
  }

  function getJson(url) {
    return request(url);
  }

  function postJson(url, body) {
    return request(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
  }

  function post(url) {
    return request(url, { method: 'POST' });
  }

  function busy(btn, on) {
    if (!btn) {
      return;
    }
    if (on) {
      btn.dataset.text = btn.textContent;
      btn.disabled = true;
      btn.textContent = '处理中...';
    } else {
      btn.disabled = false;
      if (btn.dataset.text) {
        btn.textContent = btn.dataset.text;
      }
    }
  }

  function escapeHtml(s) {
    if (s === null || s === undefined) {
      return '';
    }
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  // ------------------------------------------------------------ 服务器列表

  function loadServers(keepSelect) {
    return Promise.all([getJson('/api/servers'), getJson('/api/server-groups')]).then(function (res) {
      servers = res[0] || [];
      groups = res[1] || [];
      renderServerList();
    if (keepSelect && current) {
      var found = servers.filter(function (s) {
        return s.id === current.id;
      })[0];
      if (found) {
        current = found;
        renderPanel();
        loadTargets(current.id, true);
        return;
      }
    }
      if (!current && servers.length > 0) {
        // 默认选中「左侧树里第一个文件夹的第一台服务器」：
        // 接口返回顺序与按文件夹分组后的显示顺序不同，不能直接用 servers[0]
        select(firstServerIdInTree() || servers[0].id, true);
      }
      if (servers.length === 0) {
        current = null;
        $('panel').style.display = 'none';
        $('emptyTip').style.display = 'block';
      }
    }).catch(function (e) {
      toast(e.message, 'err');
    });
  }

  /**
   * 左侧树的分组顺序：已配置的文件夹顺序在前（含空文件夹，用于显示），
   * 其余服务器实际用到的文件夹随后，「未分组」置底。
   * 树渲染与「默认选中第一个服务器」共用这一份顺序，避免两处不一致。
   */
  function folderSections() {
    var byFolder = {};
    servers.forEach(function (s) {
      var g = s.group && s.group.trim() ? s.group.trim() : '未分组';
      (byFolder[g] = byFolder[g] || []).push(s);
    });
    var order = [];
    (groups || []).forEach(function (g) { if (order.indexOf(g) < 0) { order.push(g); } });
    Object.keys(byFolder).forEach(function (g) { if (order.indexOf(g) < 0) { order.push(g); } });
    if (order.indexOf('未分组') >= 0) {
      order.splice(order.indexOf('未分组'), 1);
      order.push('未分组');
    }
    return order.map(function (name) {
      return { name: name, list: byFolder[name] || [] };
    });
  }

  /** 树中第一个非空文件夹的第一台服务器 id（默认选中用） */
  function firstServerIdInTree() {
    var secs = folderSections();
    for (var i = 0; i < secs.length; i++) {
      if (secs[i].list.length) {
        return secs[i].list[0].id;
      }
    }
    return null;
  }

  function renderServerList() {
    var ul = $('serverList');
    ul.innerHTML = '';
    if (!servers.length) {
      return;
    }
    // 按文件夹归类（顺序见 folderSections）
    folderSections().forEach(function (sec) {
      var name = sec.name;
      var list = sec.list;
      var isUngrouped = name === '未分组';
      // 默认全部折叠：只有显式记录为 true 才展开
      var collapsed = !expandedFolders[name];
      var section = document.createElement('div');
      section.className = 'folder';
      var head = document.createElement('div');
      head.className = 'folder-head';
      head.innerHTML = '<span class="folder-toggle">' + (collapsed ? '▸' : '▾') + '</span>'
        + '<span class="folder-name">' + escapeHtml(name) + '</span>'
        + '<span class="folder-count">' + list.length + '</span>'
        + (isUngrouped ? '' : '<span class="folder-actions">'
          + '<button class="folder-rename" title="重命名文件夹">✎</button>'
          + '<button class="folder-del" title="删除文件夹">🗑</button></span>');
      var childUl = document.createElement('ul');
      childUl.className = 'folder-ul';
      if (collapsed) {
        childUl.style.display = 'none';
      }
      list.forEach(function (s) { childUl.appendChild(createServerLi(s)); });
      head.onclick = function (e) {
        if (e.target && e.target.closest('.folder-actions')) {
          return; // 点操作按钮不触发折叠
        }
        if (expandedFolders[name]) {
          expandedFolders[name] = false;  // 折叠
        } else {
          expandedFolders[name] = true;   // 展开
        }
        renderServerList();
      };
      // 文件夹头部作为拖拽放置目标：把服务器拖进来即归类
      head.addEventListener('dragover', function (e) {
        if (!dragServerId) return;
        e.preventDefault();
        head.classList.add('drop-target');
      });
      head.addEventListener('dragleave', function () {
        head.classList.remove('drop-target');
      });
      head.addEventListener('drop', function (e) {
        if (!dragServerId) return;
        e.preventDefault();
        head.classList.remove('drop-target');
        moveServerToGroup(dragServerId, name);
      });
      if (!isUngrouped) {
        var ren = head.querySelector('.folder-rename');
        if (ren) {
          ren.onclick = function (e) { e.stopPropagation(); renameGroup(name); };
        }
        var del = head.querySelector('.folder-del');
        if (del) {
          del.onclick = function (e) { e.stopPropagation(); deleteGroup(name); };
        }
      }
      section.appendChild(head);
      section.appendChild(childUl);
      ul.appendChild(section);
    });
    // 列表重建后恢复各服务器正在进行/最近的发布进度徽章
    Object.keys(serverDeployState).forEach(function (sid) { refreshServerProgress(sid); });
    // 当前服务器可能在折叠的文件夹里或因列表较长不可见：把选中项滚入可视区
    if (current) {
      var act = ul.querySelector('li.active');
      if (act && act.scrollIntoView) {
        act.scrollIntoView({ block: 'nearest' });
      }
    }
  }

  /** 把服务器移动到指定文件夹（拖拽落点触发）。folderName 为 "未分组" 则取消分组 */
  function moveServerToGroup(serverId, folderName) {
    var groupVal = folderName === '未分组' ? null : folderName;
    var s = servers.filter(function (x) { return x.id === serverId; })[0];
    if (!s) {
      return;
    }
    if ((s.group && s.group.trim()) === (groupVal || '')) {
      return; // 已在目标文件夹，无需操作
    }
    postJson('/api/servers/' + encodeURIComponent(serverId) + '/group', { group: groupVal })
      .then(function () {
        s.group = groupVal;
        // 移动到的目标若是折叠状态，展开它以便看到效果
        if (groupVal && !expandedFolders[groupVal]) {
          expandedFolders[groupVal] = true;
        }
        renderServerList();
        toast('已移动到「' + (folderName || '未分组') + '」', 'ok');
      })
      .catch(function (e) { toast(e.message, 'err'); });
  }

  /** 生成单个服务器列表项（被文件夹分组渲染复用） */
  function createServerLi(s) {
    var li = document.createElement('li');
    li.className = current && current.id === s.id ? 'active' : '';
    li.draggable = true;
    li.title = '拖拽到左侧文件夹可重新归类';
    var delBtn = '<button class=\'del-x\' title=\'删除服务器\' data-del=\'' + s.id + '\'>&times;</button>';
    var cloneBtn = '<button class=\'clone-x\' title=\'复制服务器及其全部发布配置\' data-clone=\'' + s.id + '\'>复制</button>';
    var deployBadge = '<span class="svr-deploy-badge" id="svrDeployBadge-' + s.id + '" style="display:none"></span>';
    li.innerHTML = '<div class=\'name\'><span class=\'dot\' id=\'dot-' + s.id + '\'></span>'
      + '<span class=\'nm\'>' + escapeHtml(s.name) + '</span>'
      + deployBadge + delBtn + cloneBtn + '</div>'
      + '<div class=\'meta\'>' + escapeHtml(s.username + '@' + s.host + ':' + (s.port || 22)) + '</div>';
    li.onclick = function () {
      select(s.id);
    };
    li.addEventListener('dragstart', function (e) {
      dragServerId = s.id;
      if (e.dataTransfer) {
        e.dataTransfer.effectAllowed = 'move';
        e.dataTransfer.setData('text/plain', s.id);
      }
      li.classList.add('dragging');
    });
    li.addEventListener('dragend', function () {
      dragServerId = null;
      li.classList.remove('dragging');
    });
    var delEl = li.querySelector('.del-x');
    delEl.onclick = function (e) {
      e.stopPropagation();
      deleteServer(s);
    };
    var cloneEl = li.querySelector('.clone-x');
    cloneEl.onclick = function (e) {
      e.stopPropagation();
      cloneServer(s);
    };
    return li;
  }

  // ------------------------------------------------------------ 服务器文件夹（分组）管理

  /** 新建文件夹：弹出输入框，创建后刷新列表 */
  function createGroup() {
    var name = prompt('请输入文件夹名称：');
    if (!name || !name.trim()) {
      return;
    }
    postJson('/api/server-groups', { name: name.trim() })
      .then(function (list) {
        groups = list || [];
        toast('已新建文件夹「' + name.trim() + '」', 'ok');
        renderServerList();
      })
      .catch(function (e) { toast(e.message, 'err'); });
  }

  /** 重命名文件夹：同步更新引用它的服务器 */
  function renameGroup(oldName) {
    var name = prompt('重命名文件夹「' + oldName + '」为：', oldName);
    if (!name || !name.trim() || name.trim() === oldName) {
      return;
    }
    postJson('/api/server-groups/rename', { oldName: oldName, newName: name.trim() })
      .then(function (list) {
        groups = list || [];
        var newName = name.trim();
        // 后端已同步更新各服务器的 group 引用，这里同步本地缓存，避免新旧文件夹并存
        servers.forEach(function (s) {
          if (s.group === oldName) {
            s.group = newName;
          }
        });
        // 展开状态跟随新名称
        if (expandedFolders[oldName]) {
          expandedFolders[newName] = true;
        }
        delete expandedFolders[oldName];
        toast('已重命名为「' + newName + '」', 'ok');
        renderServerList();
      })
      .catch(function (e) { toast(e.message, 'err'); });
  }

  /** 删除文件夹：其下服务器自动变为「未分组」（服务器本身不删除） */
  function deleteGroup(name) {
    if (!confirm('删除文件夹「' + name + '」？其下服务器将移动到「未分组」（服务器本身不会被删除）')) {
      return;
    }
    request('/api/server-groups/' + encodeURIComponent(name), { method: 'DELETE' })
      .then(function (list) {
        groups = list || [];
        delete expandedFolders[name];
        toast('已删除文件夹「' + name + '」', 'ok');
        renderServerList();
      })
      .catch(function (e) { toast(e.message, 'err'); });
  }

  /**
   * 选中服务器。
   * keepCollapsed 为 true 时（初始默认选中）不改动文件夹折叠状态，保持「默认全部折叠」；
   * 用户主动选中时则展开其所在文件夹，避免出现「选中了但看不到」。
   */
  function select(id, keepCollapsed) {
    current = servers.filter(function (s) {
      return s.id === id;
    })[0] || null;
    if (current && !keepCollapsed) {
      var curGroup = current.group && current.group.trim() ? current.group.trim() : '未分组';
      if (!expandedFolders[curGroup]) {
        expandedFolders[curGroup] = true;
      }
    }
    renderServerList();
    renderPanel();
    if (current) {
      // 如果该服务器有发布进度（含并行发布中的），点击服务器直接展示该台的进度；否则清空结果区
      if (serverDeployState[current.id]) {
        renderServerProgressPanel(current.id);
      } else {
        $('stepList').innerHTML = '';
        $('resultEmpty').style.display = 'block';
        $('overallBarWrap').style.display = 'none';
        $('resultBanner').style.display = 'none';
        $('seqStatus').style.display = 'none';
      }
      $('logOutput').textContent = '点击「查询」查看远程日志尾部';
      // SFTP 文件浏览默认收起，点击「SFTP 传输」按钮才展开（见 toggleSftp）
      // 切换服务器时重置终端：清空历史与本地目录，并通知服务端重置 cwd
      resetCmdTerminal();
      post('/api/servers/' + current.id + '/exec/reset').catch(function () { /* 忽略 */ });
      // 加载该服务器的发布配置并选中第一个，随后刷新状态
      loadTargets(current.id, false).then(function () {
        if (currentTarget) {
          refreshStatus();
        } else {
          $('statusCards').innerHTML = '<div class="stat"><div class="k">状态</div>'
            + '<div class="v"><span class="tag stop">未配置发布目标</span></div></div>';
        }
      });
    } else {
      $('sftpPanel').style.display = 'none';
      targets = [];
      currentTarget = null;
      renderTargets();
    }
  }

  function renderPanel() {
    if (!current) {
      return;
    }
    $('emptyTip').style.display = 'none';
    $('panel').style.display = 'block';
    $('svrName').textContent = current.name;
    var curDesc = '';
    if (currentTarget) {
      curDesc = ' &nbsp;|&nbsp; 当前发布：' + escapeHtml(currentTarget.name
        + ' (' + (currentTarget.remoteDir || '未设目录') + '/' + (currentTarget.jarName || '未设 jar') + ')');
    }
    $('svrMeta').innerHTML = escapeHtml(current.username + '@' + current.host + ':' + (current.port || 22))
      + (current.remark ? ' &nbsp;|&nbsp; ' + escapeHtml(current.remark) : '')
      + curDesc;
  }

  // ------------------------------------------------------------ 发布配置（target）

  /** 加载某台服务器的发布配置列表，并保持/选择当前发布目标 */
  function loadTargets(serverId, keepSel) {
    return getJson('/api/servers/' + serverId + '/targets').then(function (list) {
      targets = list || [];
      serverTargetCache[serverId] = targets; // 缓存，供跨服务器勾选并行发布时取 target 详情
      if (keepSel && currentTarget) {
        var found = targets.filter(function (t) { return t.id === currentTarget.id; })[0];
        currentTarget = found || targets[0] || null;
      } else {
        currentTarget = targets[0] || null;
      }
      renderTargets();
      renderPanel();
      return targets;
    }).catch(function (e) {
      toast(e.message, 'err');
      return [];
    });
  }

  /** 渲染发布配置卡片列表：每个发布配置独立操作（上传 / 本机路径 / 启停 / 回滚 / 编辑 / 删除） */
  function renderTargets() {
    var box = $('targetList');
    box.innerHTML = '';
    $('targetEmpty').style.display = targets.length ? 'none' : 'block';
    targets.forEach(function (t) {
      var isFe = t.type === 'frontend';
      var card = document.createElement('div');
      card.className = 'target-card' + (currentTarget && currentTarget.id === t.id ? ' active' : '');
      var meta;
      if (isFe) {
        meta = '前端 · ' + (t.remoteDir || '未设置目录')
          + (feFolder(t) ? '/' + feFolder(t) : '')
          + (t.zip ? ' · 压缩zip' : '') + ' · 本机: ' + (t.frontendPath || '未设置');
      } else if (isCustomBackend(t)) {
        var cj = (t.jarNames && t.jarNames.length) ? t.jarNames : (t.jarName ? [t.jarName] : []);
        meta = '自定义 · ' + (t.remoteDir || '未设置目录')
          + ' · ' + (cj.length ? (cj.length + ' 个 jar：' + cj.join(', ')) : '未设置 jar')
          + (t.confDir ? ' · conf: ' + t.confDir : '');
      } else {
        meta = (t.remoteDir || '未设置目录') + '/' + (t.jarName || '未设置 jar')
          + (t.confDir ? ' · conf: ' + t.confDir : '');
      }
      var btns = '<button class="primary" data-act="onedeploy" title="'
        + (isFe ? '按本机发布路径直接发布（上传并执行发布后命令）'
                : (isCustomBackend(t)
                    ? '选择本地 jar 包（可多选）后发布：一次上传全部 jar，再统一 停止→启动'
                    : '按该配置的目录/jar/启动参数直接发布：已配置本机路径则自动发布，否则选择本地 jar 后自动 上传→备份→停止→启动'))
        + '">一键发布</button>';
      if (isFe) {
        // 前端类型：支持选择本地文件（zip / 单文件）上传发布，也支持手动备份 / 查看历史版本并回滚（无 jar 进程启停）
        btns += '<button data-act="upload" title="选择本地文件（zip / 单文件）上传并发布到该配置">上传发布</button>'
          + '<button data-act="backup" title="把当前发布目录打包备份到备份目录">备份</button>'
          + '<button data-act="rollback" title="查看历史版本并回滚">回滚</button>';
      } else {
        // 后端 jar 应用才有启停 / 回滚（jar 进程管理）
        btns += '<button data-act="start">启动</button>'
          + '<button data-act="stop">停止</button>'
          + '<button data-act="restart">重启</button>'
          + '<button data-act="backup" title="查看历史版本并回滚">回滚</button>';
      }
      btns += '<button data-act="copytoserver" title="把该发布配置复制一份到其它服务器">跨服复制</button>'
        + '<button data-act="copy" title="基于该配置复制一份新的发布配置">复制</button>'
        + '<button data-act="edit">编辑</button>'
        + '<button class="danger" data-act="del">删除</button>';
      card.innerHTML = '<div class="target-head">'
        + '<label class="target-sel" title="勾选后可用「并行一键发布」批量发布（跨服务器同时开始）"><input type="checkbox" class="target-check"' + (selectedTargetIds[t.id] ? ' checked' : '') + '></label>'
        + '<span class="dot" id="tdot-' + t.id + '"></span>'
        + '<span class="target-name">' + escapeHtml(t.name || '未命名') + '</span>'
        + '<span class="target-meta">' + escapeHtml(meta) + '</span>'
        + '<span class="target-btns">' + btns + '</span></div>'
        + '<div class="target-sub">最近发布：'
        + (t.lastDeployTime ? '<b>' + escapeHtml(t.lastDeployTime) + '</b>' : '从未发布')
        + '</div>';
      var checkEl = card.querySelector('.target-check');
      checkEl.addEventListener('click', function (e) { e.stopPropagation(); });
      checkEl.addEventListener('change', function () {
        if (checkEl.checked) {
          selectedTargetIds[t.id] = true;
          selectedTargetMap[t.id] = t;
        } else {
          delete selectedTargetIds[t.id];
          delete selectedTargetMap[t.id];
        }
        updateSeqBtn();
      });
      card.onclick = function (e) {
        if (e.target && (e.target.tagName === 'BUTTON' || e.target.closest('.target-sel'))) {
          return;
        }
        selectTarget(t.id);
      };
      Array.prototype.forEach.call(card.querySelectorAll('button'), function (b) {
        b.onclick = function (e) {
          e.stopPropagation();
          targetAction(t, b.getAttribute('data-act'), b);
        };
      });
      // 拖拽排序：卡片本身可拖拽，也可作为放置目标（拖到某卡片之前）
      card.draggable = true;
      card.title = (card.title ? card.title + '；' : '') + '拖拽可调整顺序';
      card.addEventListener('dragstart', function (e) {
        dragTargetId = t.id;
        if (e.dataTransfer) {
          e.dataTransfer.effectAllowed = 'move';
          e.dataTransfer.setData('text/plain', t.id);
        }
        card.classList.add('dragging');
      });
      card.addEventListener('dragend', function () {
        dragTargetId = null;
        card.classList.remove('dragging');
      });
      card.addEventListener('dragover', function (e) {
        if (!dragTargetId || dragTargetId === t.id) return;
        e.preventDefault();
        card.classList.add('drop-target');
      });
      card.addEventListener('dragleave', function () {
        card.classList.remove('drop-target');
      });
      card.addEventListener('drop', function (e) {
        if (!dragTargetId || dragTargetId === t.id) return;
        e.preventDefault();
        card.classList.remove('drop-target');
        reorderTargets(dragTargetId, t.id);
      });
      box.appendChild(card);
    });
    updateSeqBtn();
  }

  /** 根据勾选状态更新「按顺序一键发布」按钮（计数 / 禁用）与全选框状态 */
  function updateSeqBtn() {
    var btn = $('btnDeploySequential');
    if (btn) {
      var count = 0;
      for (var k in selectedTargetIds) {
        if (selectedTargetIds.hasOwnProperty(k) && selectedTargetIds[k]) {
          count++;
        }
      }
      btn.disabled = count === 0 || sequentialDeploying;
      btn.textContent = count > 0 ? ('并行一键发布 (' + count + ')') : '并行一键发布';
    }
    var selAll = $('targetSelectAll');
    if (selAll) {
      // 全选框只统计当前服务器的勾选（勾选本身可跨服务器）
      var total = targets.length;
      var sel = 0;
      targets.forEach(function (t) { if (selectedTargetIds[t.id]) { sel++; } });
      selAll.checked = total > 0 && sel === total;
      selAll.indeterminate = sel > 0 && sel < total;
    }
    var rb = $('btnRestartBatch');
    if (rb) {
      rb.disabled = sequentialDeploying || batchRestarting;
    }
  }

  /** 显示/更新「顺序发布 / 批量重启」进度状态条（位于执行结果区，清晰展示当前是哪个工程） */
  function showSeqStatus(text, type) {
    var el = $('seqStatus');
    if (!el) {
      return;
    }
    el.style.display = 'block';
    el.className = 'seq-status' + (type ? ' ' + type : '');
    el.textContent = text;
  }

  /** 把发布配置 draggedId 拖动到 beforeId 之前，更新本地顺序并持久化 */
  function reorderTargets(draggedId, beforeId) {
    if (!current || !draggedId || draggedId === beforeId) {
      return;
    }
    var dragged = targets.filter(function (t) { return t.id === draggedId; })[0];
    if (!dragged) {
      return;
    }
    var without = targets.filter(function (t) { return t.id !== draggedId; });
    var idx = -1;
    for (var i = 0; i < without.length; i++) {
      if (without[i].id === beforeId) {
        idx = i;
        break;
      }
    }
    if (idx < 0) {
      idx = without.length;
    }
    without.splice(idx, 0, dragged);
    targets = without;
    renderTargets(); // 立即重排，提升交互即时感
    postJson('/api/servers/' + current.id + '/targets/reorder', { ids: targets.map(function (t) { return t.id; }) })
      .then(function () { toast('已调整发布配置顺序', 'ok'); })
      .catch(function (e) {
        toast(e.message, 'err');
        loadTargets(current.id, true); // 失败回滚：重新从后端拉取真实顺序
      });
  }

  /** 选中某个发布配置：状态 / 日志 / 备份等切换到该配置 */
  function selectTarget(id) {
    var t = targets.filter(function (x) { return x.id === id; })[0] || null;
    currentTarget = t;
    renderTargets();
    renderPanel();
    if (currentTarget) {
      refreshStatus();
      if ($('tab-backup').style.display === 'block') {
        loadBackups();
      }
      if ($('tab-log').style.display === 'block') {
        loadLog();
      }
    }
  }

  /** 发布配置卡片上的操作分发 */
  function targetAction(t, act, btn) {
    if (!current || !t) {
      return;
    }
    if ((act === 'onedeploy' || act === 'upload') && (sequentialDeploying || batchRestarting)) {
      toast('批量任务执行中，请等待完成后再单独操作', 'err');
      return;
    }
    if ((act === 'start' || act === 'stop' || act === 'restart') && (sequentialDeploying || batchRestarting)) {
      toast('批量任务执行中，请等待完成后再单独操作', 'err');
      return;
    }
    var isFe = t.type === 'frontend';
    currentTarget = t;
    renderTargets();
    renderPanel();
    if (act === 'onedeploy') {
      if (isFe) {
        // 前端类型：直接用配置中的本机发布路径发布
        frontendDeploy(t, null);
      } else if (isCustomBackend(t)) {
        // 自定义部署：不走本机路径发布，直接选择（可多选）jar 包统一上传更新
        fallbackFilePicker(t);
      } else if (t.localPath && t.localPath.trim()) {
        // 若发布配置里已保存「本机路径」，直接用它一键发布（deploy-manager 与 jar 同机时生效）
        localDeploy(t, t.localPath.trim(), t.restartLocal !== false, null);
      } else {
        // 未配置本机路径：走浏览器目录句柄上传（免选文件）；不支持的浏览器退回普通文件选择
        uploadViaDirHandle(t);
      }
    } else if (act === 'upload') {
      // 前端类型选 zip / 单文件，后端类型选 jar（服务端按配置类型分流发布）；自定义部署可多选
      var jf = isFe ? $('feUploadFile') : $('jarFile');
      jf.value = '';
      jf.multiple = !isFe && isCustomBackend(t);
      jf.click();
    } else if (act === 'start' || act === 'stop' || act === 'restart') {
      if (act === 'stop' && !confirm('确定停止「' + (t.name || '') + '」吗？')) {
        return;
      }
      appAction(t, act, btn);
    } else if (act === 'rollback') {
      switchTab('backup');
    } else if (act === 'backup') {
      if (t.type === 'frontend') {
        // 前端类型：手动备份发布目录
        manualBackup(t, btn);
      } else {
        switchTab('backup');
      }
    } else if (act === 'edit') {
      openTargetModal(t);
    } else if (act === 'copy') {
      copyTarget(t);
    } else if (act === 'copytoserver') {
      openCopyTo(t);
    } else if (act === 'del') {
      deleteTarget(t);
    }
  }

  /** 复制发布配置：基于已有配置生成一份同服务器下的新配置，复制成功后自动打开编辑弹窗便于改名 */
  function copyTarget(t) {
    if (!current || !t) {
      return;
    }
    request('/api/servers/' + current.id + '/targets/' + t.id + '/copy', { method: 'POST' })
      .then(function (saved) {
        toast('已复制为「' + (saved.name || '') + '」，可修改后保存', 'ok');
        return loadTargets(current.id, true).then(function () {
          var copied = targets.filter(function (x) { return x.id === saved.id; })[0];
          if (copied) {
            openTargetModal(copied);
          }
          return copied;
        });
      })
      .catch(function (e) {
        toast(e.message, 'err');
      });
  }

  /** 打开「跨服复制」弹窗：列出其它服务器供选择目标 */
  function openCopyTo(t) {
    if (!current || !t) {
      return;
    }
    var sel = $('copyToServerSel');
    sel.innerHTML = '';
    var others = (servers || []).filter(function (s) { return s.id !== current.id; });
    if (others.length === 0) {
      toast('没有其它服务器可作为复制目标', 'err');
      return;
    }
    others.forEach(function (s) {
      var o = document.createElement('option');
      o.value = s.id;
      o.textContent = s.name + ' (' + s.username + '@' + s.host + ':' + (s.port || 22) + ')';
      sel.appendChild(o);
    });
    $('copyToModalMask').dataset.targetId = t.id;
    $('copyToModalMask').classList.add('show');
  }

  /** 确认跨服复制：把当前发布配置复制到所选服务器（保留原名称） */
  function confirmCopyTo() {
    var mask = $('copyToModalMask');
    var targetId = mask.dataset.targetId;
    var toServer = $('copyToServerSel').value;
    if (!targetId || !toServer) {
      return;
    }
    request('/api/servers/' + current.id + '/targets/' + targetId + '/copy-to/' + toServer, { method: 'POST' })
      .then(function (saved) {
        toast('已复制到「' + (saved.name || '') + '」', 'ok');
        closeCopyTo();
        if (toServer === current.id) {
          loadTargets(current.id, true);
        }
      })
      .catch(function (e) {
        toast(e.message, 'err');
      });
  }

  function closeCopyTo() {
    $('copyToModalMask').classList.remove('show');
  }

  var TARGET_FIELDS = ['id', 'serverId', 'name', 'remoteDir', 'confDir', 'jarName', 'javaCmd', 'jvmOpts',
    'appArgs', 'infoLogFile', 'errorLogFile', 'backupDir', 'preStartCmd', 'postStartCmd', 'localPath',
    'type', 'frontendPath', 'frontendPostCmd', 'zip', 'deployMode', 'startCommand'];

  /** 弹窗内「自定义部署」的 jar 包列表（多个 jar 统一上传更新） */
  var jarChips = [];

  /** 当前弹窗选中的后端部署方式：default / custom */
  function currentDeployMode() {
    var rs = document.getElementsByName('t-deployMode');
    for (var i = 0; i < rs.length; i++) {
      if (rs[i].checked) { return rs[i].value; }
    }
    return 'default';
  }

  /** 该配置是否为「自定义部署」的后端配置 */
  function isCustomBackend(t) {
    return !!t && t.type !== 'frontend' && t.deployMode === 'custom';
  }

  /** 渲染弹窗内的 jar 包标签列表 */
  function renderJarChips() {
    var box = $('t-jarNameList');
    if (!box) { return; }
    box.innerHTML = '';
    if (!jarChips.length) {
      box.innerHTML = '<span class="jar-chip-empty">尚未上传 jar 包，选择上方文件并保存后会自动识别</span>';
      return;
    }
    jarChips.forEach(function (n, idx) {
      var chip = document.createElement('span');
      chip.className = 'jar-chip';
      var nameEl = document.createElement('span');
      nameEl.textContent = n;
      var del = document.createElement('button');
      del.type = 'button';
      del.className = 'jar-chip-del';
      del.textContent = '×';
      del.title = '移除该 jar';
      del.onclick = function () {
        jarChips.splice(idx, 1);
        renderJarChips();
        previewStartCmd();
      };
      chip.appendChild(nameEl);
      chip.appendChild(del);
      box.appendChild(chip);
    });
  }

  /** 追加 jar 名称（支持逗号/空格分隔批量输入；去重、只保留 .jar、去掉路径前缀） */
  function addJarChips(names) {
    var rawList = [];
    (names || []).forEach(function (raw) {
      String(raw == null ? '' : raw).split(/[,;，；\s]+/).forEach(function (seg) {
        if (seg) { rawList.push(seg); }
      });
    });
    rawList.forEach(function (raw) {
      var n = String(raw).trim();
      if (!n) { return; }
      n = n.replace(/^.*[\\\/]/, '');
      if (!/\.jar$/i.test(n)) { return; }
      if (jarChips.indexOf(n) < 0) { jarChips.push(n); }
    });
    renderJarChips();
    previewStartCmd();
  }

  /**
   * 由本机发布路径推导服务器部署文件夹名（取末级目录名），与后端 deployFolderName() 保持一致。
   * 如 /opt/build/web → web；/Users/me/project/dist → dist。
   */
  function pathFolderName(p) {
    if (!p) { return ''; }
    p = String(p).trim().replace(/[\\/]+$/, '');
    var k = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
    var seg = k >= 0 ? p.substring(k + 1) : p;
    return seg;
  }

  /** 前端类型派生文件夹名（供展示用） */
  function feFolder(t) {
    return t && t.frontendPath ? pathFolderName(t.frontendPath) : '';
  }

  /**
   * 默认解压命令模板：先 cd 到发布目录，unzip 解压（zip 名为「文件夹名.zip」，由本机发布路径推导），
   * 再删除 zip 包。注意：不自动生成 -d 子目录参数，解压产物落在「发布目录/文件夹名」下（与 effectiveFrontendDir 一致）。
   */
  function defaultUnzipCmd(remoteDir, folder) {
    var dir = remoteDir && remoteDir.trim() ? remoteDir.trim() : '/opt/app/frontend';
    var name = folder && folder.trim() ? folder.trim() : 'dist';
    return 'cd ' + dir + ' && unzip -o ' + name + '.zip && rm -f ' + name + '.zip';
  }

  /** 仅当发布后命令仍为「自动填充的默认解压命令」或为空时，允许被覆盖（兼容旧版带 -d 的自动命令） */
  function isAutoUnzipCmd(v) {
    return !v || /^\s*cd\s+.*&&\s*unzip\s+-o\s+(\S+?)\.zip\s+(-d\s+\S+\s+)?&&\s*rm\s+-f\s+\1\.zip\s*$/.test(v);
  }

  /** 前端类型：根据是否勾选「压缩」自动填充 / 清空发布后执行命令（解压文件名取本机发布路径推导的文件夹名） */
  function syncFrontendPostCmd() {
    var typeRadios = document.getElementsByName('t-type');
    var type = 'backend';
    for (var i = 0; i < typeRadios.length; i++) {
      if (typeRadios[i].checked) { type = typeRadios[i].value; break; }
    }
    if (type !== 'frontend') { return; }
    var zip = $('t-zip') && $('t-zip').checked;
    var pc = $('t-frontendPostCmd');
    if (!pc) { return; }
    if (zip) {
      var folder = pathFolderName($('t-frontendPath') ? $('t-frontendPath').value : '');
      if (isAutoUnzipCmd(pc.value)) {
        pc.value = defaultUnzipCmd($('t-remoteDir') ? $('t-remoteDir').value : '', folder);
      }
    }
    // 取消勾选时不强制清空（保留用户可能手填的命令），仅不再自动填充
  }

  /** 根据配置类型（backend/frontend）显示 / 隐藏对应字段组 */
  function applyTargetTypeUI(type) {
    var isFrontend = type === 'frontend';
    var be = document.querySelectorAll('.backend-only');
    var fe = document.querySelectorAll('.frontend-only');
    be.forEach(function (el) { el.style.display = isFrontend ? 'none' : ''; });
    fe.forEach(function (el) { el.style.display = isFrontend ? '' : 'none'; });
    // 同一个目录输入框在两种类型下的叫法不同：后端是「jar 包目录」，前端是「发布目录」
    var rl = $('t-remoteDirLabel');
    if (rl) { rl.textContent = isFrontend ? '发布目录 *' : 'jar 包目录 *'; }
    var rh = $('t-remoteDirHint');
    if (rh) {
      rh.textContent = isFrontend
        ? '静态资源上传到该目录，目录不存在会自动创建'
        : 'jar 包上传到该目录，目录不存在会自动创建；应用也在该目录下启动';
    }
    var rd = $('t-remoteDir');
    if (rd) { rd.placeholder = isFrontend ? '/opt/app/web' : '/opt/app/order'; }
    syncFrontendPostCmd();
    applyDeployModeUI(currentDeployMode());
  }

  /**
   * 后端部署方式的子分类切换：
   * - 默认部署（default）：单个 jar + 自动生成默认启动命令 + 本机路径发布；
   * - 自定义部署（custom）：多个 jar 统一上传更新，不含本机路径发布。
   * 「自定义启动命令」为两种方式共用的选填项。
   */
  function applyDeployModeUI(mode) {
    var isCustom = mode === 'custom';
    document.querySelectorAll('.custom-only').forEach(function (el) {
      el.style.display = isCustom ? '' : 'none';
    });
    document.querySelectorAll('.default-only').forEach(function (el) {
      el.style.display = isCustom ? 'none' : '';
    });
    previewStartCmd();
  }

  /** 取表单字段值（空串兜底），并去掉首尾空白 */
  function fv(id) {
    var el = document.getElementById(id);
    return el ? el.value : '';
  }

  /** 去掉路径结尾斜杠 */
  function trimEndSlash(p) {
    p = (p || '').trim();
    while (p.length > 1 && (p.endsWith('/') || p.endsWith('\\'))) {
      p = p.substring(0, p.length - 1);
    }
    return p;
  }

  /** 去扩展名（取 jar 名主体，用于默认日志名） */
  function stripExtClient(name) {
    name = (name || '').trim();
    var i = name.lastIndexOf('.');
    return i > 0 ? name.substring(0, i) : name;
  }

  /** 取当前表单里的 jar 名称列表（自定义部署用标签列表，默认部署用单个 jar 名称） */
  function formJarNames() {
    if (currentDeployMode() === 'custom') {
      return jarChips.slice();
    }
    var single = fv('t-jarName').trim();
    return single ? [single] : [];
  }

  /** 把自定义启动命令里的占位符替换为表单当前值（与后端 DeployTarget.resolvedStartCommand 保持一致） */
  function resolveStartPlaceholders(cmd) {
    var remoteDir = trimEndSlash(fv('t-remoteDir'));
    var names = formJarNames();
    // 自定义部署以列表首项作为「主 jar」，默认部署就是唯一的 jar 名称
    var jarName = currentDeployMode() === 'custom' ? (names[0] || '') : fv('t-jarName').trim();
    var jars = names.map(function (n) { return (remoteDir ? remoteDir + '/' : '') + n; }).join(' ');
    var base = stripExtClient(jarName || names[0] || '');
    var infoLog = fv('t-infoLogFile').trim()
      || (remoteDir ? remoteDir + '/logs/' + (base || 'app') + '.log' : '<日志文件>');
    var errorLog = fv('t-errorLogFile').trim()
      || (remoteDir ? remoteDir + '/logs/' + (base || 'app') + '-error.log' : '<error日志文件>');
    var pidFile = remoteDir ? remoteDir + '/' + (base || 'app') + '.pid' : '<pid文件>';
    var backupDir = fv('t-backupDir').trim() || (remoteDir ? remoteDir + '/backup' : '<备份目录>');
    var confDir = trimEndSlash(fv('t-confDir')) || (remoteDir ? remoteDir + '/conf' : '<conf目录>');
    return cmd
      .replace(/\{remoteDir\}/g, remoteDir || '<发布目录>')
      .replace(/\{confDir\}/g, confDir)
      .replace(/\{jarPath\}/g, jarName ? (remoteDir ? remoteDir + '/' + jarName : jarName) : (jars || '<jar名>'))
      .replace(/\{jarName\}/g, jarName || '<jar名>')
      .replace(/\{jars\}/g, jars || '<jar名>')
      .replace(/\{logFile\}/g, infoLog)
      .replace(/\{infoLogFile\}/g, infoLog)
      .replace(/\{errorLogFile\}/g, errorLog)
      .replace(/\{pidFile\}/g, pidFile)
      .replace(/\{javaCmd\}/g, fv('t-javaCmd').trim() || 'java')
      .replace(/\{jvmOpts\}/g, fv('t-jvmOpts').trim())
      .replace(/\{appArgs\}/g, fv('t-appArgs').trim())
      .replace(/\{backupDir\}/g, backupDir);
  }

  /** 实时预览后端启动命令（随表单字段变化更新；前端类型隐藏） */
  function previewStartCmd() {
    var wrap = $('startCmdText');
    if (!wrap) { return; }
    var typeRadios = document.getElementsByName('t-type');
    var type = 'backend';
    for (var i = 0; i < typeRadios.length; i++) {
      if (typeRadios[i].checked) { type = typeRadios[i].value; break; }
    }
    if (type !== 'backend') {
      wrap.textContent = '';
      return;
    }
    var remoteDir = trimEndSlash(fv('t-remoteDir'));
    // 填写了自定义启动命令：完全覆盖默认命令，预览里也不再自动加 cd（需要 cd 请在命令中自行书写）
    var custom = fv('t-startCommand').trim();
    if (custom) {
      wrap.textContent = resolveStartPlaceholders(custom);
      return;
    }
    var jarName = fv('t-jarName').trim();
    var javaCmd = fv('t-javaCmd').trim() || 'java';
    var jvmOpts = fv('t-jvmOpts').trim();
    var appArgs = fv('t-appArgs').trim();
    var logFile = fv('t-infoLogFile').trim();

    var jarPath = jarName
      ? (remoteDir ? remoteDir + '/' + jarName : jarName)
      : (remoteDir ? remoteDir + '/<jar名>' : '<jar名>');
    var log = logFile
      ? logFile
      : (remoteDir ? remoteDir + '/logs/' + (stripExtClient(jarName) || 'app') + '.log' : '<日志文件>');

    var parts = [];
    parts.push('cd ' + (remoteDir || '<发布目录>'));
    var line2 = 'nohup ' + javaCmd;
    if (jvmOpts) { line2 += ' ' + jvmOpts; }
    line2 += ' -jar ' + jarPath;
    if (appArgs) { line2 += ' ' + appArgs; }
    line2 += ' >> ' + log + ' 2>&1 &';
    parts.push(line2);
    wrap.textContent = parts.join('\n');
  }

  /** 打开发布配置弹窗（target 为空则新增） */
  function openTargetModal(target) {
    if (!current) {
      return;
    }
    TARGET_FIELDS.forEach(function (f) {
      var el = $('t-' + f);
      if (!el) {
        return;
      }
      var v = target ? target[f] : '';
      el.value = v === null || v === undefined ? '' : v;
    });
    // 清空「选择 jar 文件上传」的文件选择，避免上一次残留
    var jf = $('t-jarFile');
    if (jf) {
      jf.value = '';
    }
    var jfs = $('t-jarFiles');
    if (jfs) {
      jfs.value = '';
      // 选文件时不直接写入 jar 包列表：只有真正上传成功后（服务端自动登记）才会出现在列表里，
      // 避免列表里出现尚未上传、服务器上并不存在的 jar 名
      jfs.onchange = function () {
        var n = this.files ? this.files.length : 0;
        if (n > 0) {
          toast('已选择 ' + n + ' 个文件，点「保存并上传文件」后上传并自动加入列表', 'ok');
        }
      };
    }
    // 清空「上传 conf 文件」的文件选择，避免上一次残留
    var cf = $('t-confFiles');
    if (cf) {
      cf.value = '';
    }
    // 本机路径发布后的重启开关（复选框单独处理）
    var rl = $('t-restartLocal');
    if (rl) {
      rl.checked = target ? (target.restartLocal !== false) : true;
    }
    if (!target) {
      $('t-serverId').value = current.id;
      $('t-javaCmd').value = 'java';
      $('t-jvmOpts').value = '';
    }
    // 类型单选：回填或默认后端，并按类型切换字段显示
    var type = target && target.type ? target.type : 'backend';
    var radios = document.getElementsByName('t-type');
    for (var i = 0; i < radios.length; i++) {
      radios[i].checked = radios[i].value === type;
      radios[i].onchange = function () { applyTargetTypeUI(this.value); };
    }
    // 后端部署方式子分类：默认部署 / 自定义部署
    var mode = target && target.deployMode === 'custom' ? 'custom' : 'default';
    var mradios = document.getElementsByName('t-deployMode');
    for (var m = 0; m < mradios.length; m++) {
      mradios[m].checked = mradios[m].value === mode;
      mradios[m].onchange = function () { applyDeployModeUI(this.value); };
    }
    // 自定义部署的 jar 包列表：优先用已保存的多个 jar，没有则用单个 jar 名称兜底
    var initJars = [];
    if (target) {
      if (target.jarNames && target.jarNames.length) {
        initJars = target.jarNames.slice();
      } else if (target.jarName) {
        initJars = [target.jarName];
      }
    }
    jarChips = [];
    addJarChips(initJars);
    // 自定义启动命令变化时刷新预览
    var scEl = $('t-startCommand');
    if (scEl) {
      scEl.oninput = previewStartCmd;
    }
    // 前端「压缩成 zip」勾选框回填 + 切换时同步解压命令
    var zc = $('t-zip');
    if (zc) {
      zc.checked = !!(target && target.zip);
      zc.onchange = function () { syncFrontendPostCmd(); };
    }
    // 本机发布路径变化时，若使用默认解压命令则同步更新其中的 zip 文件名（取路径末级目录名）
    var fp = $('t-frontendPath');
    if (fp) {
      fp.oninput = function () {
        var ty = document.getElementsByName('t-type');
        var t0 = 'backend';
        for (var i = 0; i < ty.length; i++) { if (ty[i].checked) { t0 = ty[i].value; break; } }
        if (t0 === 'frontend' && zc && zc.checked && isAutoUnzipCmd($('t-frontendPostCmd').value)) {
          var fld = pathFolderName(fp.value);
          $('t-frontendPostCmd').value = defaultUnzipCmd($('t-remoteDir') ? $('t-remoteDir').value : '', fld);
        }
      };
    }
    // 前端「更新方式」单选（覆盖更新 / 清空后更新）
    var dm0 = $('t-deploy-mode-overwrite');
    var dm1 = $('t-deploy-mode-clean');
    if (dm0 && dm1) {
      var cleanMode = !!(target && target.clearBeforeDeploy);
      dm0.checked = !cleanMode;
      dm1.checked = cleanMode;
    }
    // 「发布前备份」勾选框：后端 / 前端各一个，共用 backupEnabled 字段，默认不勾选
    var be = $('t-backupEnabled');
    var beFe = $('t-backupEnabledFe');
    var backupOn = !!(target && target.backupEnabled);
    if (be) { be.checked = backupOn; }
    if (beFe) { beFe.checked = backupOn; }
    // 发布目录变化也同步默认解压命令中的目录
    var rd = $('t-remoteDir');
    if (rd) {
      rd.oninput = function () {
        var tr = document.getElementsByName('t-type');
        var ty = 'backend';
        for (var i = 0; i < tr.length; i++) { if (tr[i].checked) { ty = tr[i].value; break; } }
        if (ty === 'frontend' && zc && zc.checked && isAutoUnzipCmd($('t-frontendPostCmd').value)) {
          var fld = pathFolderName($('t-frontendPath') ? $('t-frontendPath').value : '');
          $('t-frontendPostCmd').value = defaultUnzipCmd(rd.value, fld);
        }
        previewStartCmd();
      };
    }
    // 启动命令预览：相关字段变化时实时刷新
    ['t-jarName', 't-javaCmd', 't-jvmOpts', 't-appArgs', 't-infoLogFile', 't-errorLogFile'].forEach(function (id) {
      var el = $(id);
      if (el) { el.oninput = previewStartCmd; }
    });
    // 复制启动命令
    var copyBtn = $('btnCopyStartCmd');
    if (copyBtn) {
      copyBtn.onclick = function () {
        var txt = $('startCmdText') ? $('startCmdText').textContent : '';
        if (!txt) { return; }
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(txt).then(function () { toast('已复制启动命令', 'ok'); },
            function () { toast('复制失败', 'err'); });
        } else {
          toast('当前环境不支持复制', 'err');
        }
      };
    }
    applyTargetTypeUI(type);
    $('targetModalTitle').textContent = target ? '编辑发布配置' : '新增发布配置';
    $('targetModalMask').classList.add('show');
  }

  function closeTargetModal() {
    $('targetModalMask').classList.remove('show');
  }

  /**
   * 保存发布配置。
   * @param btn 触发的按钮（用于 loading）
   * @param withFiles 是否同时上传弹窗里选中的 jar / conf 文件；
   *        为 false 时只保存配置，不上传、不备份、不启停（部署只能由外层「一键发布」触发）
   */
  function saveTarget(btn, withFiles) {
    if (!current) {
      return;
    }
    var body = {};
    TARGET_FIELDS.forEach(function (f) {
      var el = $('t-' + f);
      if (el) {
        body[f] = el.value.trim();
      }
    });
    // 复选框需读 .checked（而非 value）
    var zc = $('t-zip');
    if (zc) {
      body.zip = zc.checked;
    }
    // 前端更新方式单选（覆盖更新 / 清空后更新）
    var dm = document.querySelector('input[name="t-deploy-mode"]:checked');
    if (dm) {
      body.clearBeforeDeploy = dm.value === 'clean';
    }
    // 「发布前备份」：后端 / 前端两个勾选框任一勾选即视为开启（默认不勾选）
    var be = $('t-backupEnabled');
    var beFe = $('t-backupEnabledFe');
    body.backupEnabled = !!(be && be.checked) || !!(beFe && beFe.checked);
    // 本机路径发布后的重启开关（复选框）
    var rl = $('t-restartLocal');
    if (rl) {
      body.restartLocal = rl.checked;
    }
    // 配置类型（后端 / 前端），读取选中的单选值
    var typeRadios = document.getElementsByName('t-type');
    for (var i = 0; i < typeRadios.length; i++) {
      if (typeRadios[i].checked) {
        body.type = typeRadios[i].value;
        break;
      }
    }
    if (!body.type) {
      body.type = 'backend';
    }
    // 后端部署方式子分类：默认部署 / 自定义部署
    var mode = currentDeployMode();
    body.deployMode = body.type === 'frontend' ? 'default' : mode;
    if (body.type !== 'frontend' && mode === 'custom') {
      body.jarNames = jarChips.slice();
      body.startCommand = fv('t-startCommand').trim();
      // 主 jar 名称与列表首项保持一致，供默认日志名 / {jarName} 占位符使用；列表清空则一并清空
      body.jarName = jarChips.length ? jarChips[0] : '';
      // 自定义部署不含「本机路径发布」
      delete body.localPath;
      delete body.restartLocal;
    } else if (body.type !== 'frontend') {
      body.jarNames = [];
      // 默认部署同样支持自定义启动命令（留空则用自动生成的默认命令）
      body.startCommand = fv('t-startCommand').trim();
    }
    // 前端发布静态资源：不设置任何后端（jar）相关字段
    if (body.type === 'frontend') {
      ['jarName', 'javaCmd', 'jvmOpts', 'appArgs', 'infoLogFile', 'errorLogFile', 'backupDir', 'preStartCmd', 'postStartCmd', 'localPath', 'restartLocal', 'startCommand', 'jarNames']
        .forEach(function (f) { delete body[f]; });
    }
    if (!body.serverId) {
      body.serverId = current.id;
    }
    // 只有点「保存并上传文件」时才处理所选文件；单纯点「保存」不产生任何远程动作（不上传、不备份、不启停）
    var pickedFiles = [];
    var pickedConf = [];
    if (withFiles && body.type !== 'frontend') {
      if (body.deployMode === 'custom') {
        var jfs = $('t-jarFiles');
        if (jfs && jfs.files) {
          pickedFiles = Array.prototype.slice.call(jfs.files);
        }
      } else {
        var jf = $('t-jarFile');
        if (jf && jf.files && jf.files[0]) {
          pickedFiles = [jf.files[0]];
        }
        // 默认部署上传依赖「jar 包名称」作为远程文件名，未填写时用所选文件名回填
        if (pickedFiles.length && !body.jarName) {
          body.jarName = pickedFiles[0].name;
        }
      }
      // conf 文件（yml 等）：上传到 conf 目录，不参与重启流程
      var cfEl = $('t-confFiles');
      if (cfEl && cfEl.files) {
        pickedConf = Array.prototype.slice.call(cfEl.files);
      }
    }
    var isUpdate = !!body.id;
    var url = '/api/servers/' + current.id + '/targets' + (isUpdate ? '/' + body.id : '');
    busy(btn, true);
    request(url, {
      method: isUpdate ? 'PUT' : 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }).then(function (saved) {
      var files = pickedFiles;
      var confs = pickedConf;
      var uploading = files.length > 0 || confs.length > 0;
      closeTargetModal();
      toast(uploading ? '配置已保存，正在上传文件（不重启应用）…' : '已保存，未上传文件、未部署', 'ok');
      return loadTargets(current.id, true).then(function () {
        if (currentTarget) {
          refreshStatus();
        }
        if (uploading && saved && saved.id) {
          return { files: files, conf: confs, id: saved.id };
        }
      });
    }).then(function (ctx) {
      if (!ctx || !ctx.id) {
        return;
      }
      // 只上传文件：不备份、不停止、不重启；需要完整部署请在外层卡片点「一键发布」
      return uploadConfFiles(ctx.conf, ctx.id).then(function () {
        if (ctx.files && ctx.files.length) {
          uploadPickedFiles(ctx.files, ctx.id, 'false');
        }
      });
    }).catch(function (e) {
      toast(e.message, 'err');
    }).then(function () {
      busy(btn, false);
    });
  }

  function deleteTarget(t) {
    if (!current || !t) {
      return;
    }
    if (!confirm('确定删除发布配置「' + (t.name || '') + '」吗？（只删除本地配置，不影响服务器上的文件）')) {
      return;
    }
    request('/api/servers/' + current.id + '/targets/' + t.id, { method: 'DELETE' })
      .then(function () {
        toast('已删除', 'ok');
        return loadTargets(current.id, false);
      }).then(function () {
        if (currentTarget) {
          refreshStatus();
        } else {
          $('statusCards').innerHTML = '<div class="stat"><div class="k">状态</div>'
            + '<div class="v"><span class="tag stop">未配置发布目标</span></div></div>';
        }
      }).catch(function (e) {
        toast(e.message, 'err');
      });
  }

  // ------------------------------------------------------------ 状态

  function refreshStatus(btn) {
    if (!current) {
      return;
    }
    if (!currentTarget) {
      $('statusCards').innerHTML = '<div class="stat"><div class="k">状态</div>'
        + '<div class="v"><span class="tag stop">未配置发布目标</span></div></div>';
      return;
    }
    busy(btn, true);
    $('statusCards').innerHTML = '<div class="stat"><div class="k">状态</div><div class="v"><span class="loading"></span> 查询中</div></div>';
    getJson('/api/apps/' + currentTarget.id + '/status').then(function (st) {
      renderStatus(st);
    }).catch(function (e) {
      $('statusCards').innerHTML = '<div class="stat"><div class="k">状态</div><div class="v"><span class="tag err">查询失败</span></div></div>';
      toast(e.message, 'err');
    }).then(function () {
      busy(btn, false);
    });
  }

  /** 启动时间统一为 YYYY-MM-DD HH:mm:ss（后端已格式化；这里兜底 ps lstart 的英文格式） */
  function fmtDateTime(v) {
    var s = String(v == null ? '' : v).trim();
    if (!s) { return '-'; }
    if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(s)) { return s; }
    var m = s.match(/^(?:[A-Za-z]{3}\s+)?([A-Za-z]{3})\s+(\d{1,2})\s+(\d{2}:\d{2}:\d{2})\s+(\d{4})$/);
    if (m) {
      var mon = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']
        .indexOf(m[1]) + 1;
      if (mon > 0) {
        var d = m[2].length < 2 ? '0' + m[2] : m[2];
        return m[4] + '-' + (mon < 10 ? '0' : '') + mon + '-' + d + ' ' + m[3];
      }
    }
    return s;
  }

  function renderStatus(st) {
    var tag = !st.connected ? '<span class="tag err">连接失败</span>'
      : (st.running ? '<span class="tag run">运行中</span>' : '<span class="tag stop">未运行</span>');
    var cards = [
      ['运行状态', tag],
      ['进程 PID', escapeHtml(st.pid || '-')],
      ['启动时间', escapeHtml(fmtDateTime(st.startTime))],
      ['运行时长', escapeHtml(st.uptime || '-')],
      ['占用内存', escapeHtml(st.memory || '-')],
      ['远程 jar', escapeHtml(st.jarInfo || '未找到')],
      ['info 日志', escapeHtml(st.logFile || '-')],
      ['error 日志', escapeHtml(st.errorLogFile || '-')]
    ];
    if (!st.connected && st.message) {
      cards.push(['失败原因', escapeHtml(st.message)]);
    }
    $('statusCards').innerHTML = cards.map(function (c) {
      return '<div class="stat"><div class="k">' + c[0] + '</div><div class="v">' + c[1] + '</div></div>';
    }).join('');

    // 发布配置卡片上的状态点：按运行状态着色
    var tdot = $('tdot-' + st.targetId);
    if (tdot) {
      tdot.className = 'dot ' + (!st.connected ? 'err' : (st.running ? 'run' : ''));
    }
    // 服务器列表状态点：连接成功即绿色
    var dot = $('dot-' + st.serverId);
    if (dot) {
      dot.className = 'dot ' + (!st.connected ? 'err' : 'run');
    }
  }

  // ------------------------------------------------------------ 发布 / 启停

  function renderResult(result) {
    $('resultEmpty').style.display = 'none';
    $('overallBarWrap').style.display = 'none';
    switchTab('result');
    var html = result.steps.map(function (s) {
      var skipped = (!s.success && !s.detail);
      var cls = skipped ? 'skip' : (s.success ? 'ok' : 'no');
      var ico = skipped ? '–' : (s.success ? '✓' : '✗');
      return '<li>'
        + '<span class="ico ' + cls + '">' + ico + '</span>'
        + '<span class="name">' + escapeHtml(s.name) + '</span>'
        + '<span class="detail">' + escapeHtml(skipped ? '未执行（前序步骤失败，已中止）' : s.detail) + '</span>'
        + '<span class="cost">' + s.costMs + 'ms</span>'
        + '</li>';
    }).join('');
    $('stepList').innerHTML = html;
    // 失败时用醒目横幅 + 红色 toast 明确提示，避免只停在“执行中”
    var banner = $('resultBanner');
    if (result.success) {
      banner.style.display = 'none';
    } else {
      banner.style.display = 'block';
      banner.className = 'result-banner err';
      banner.textContent = '❌ 执行失败：' + (result.message || '未知错误');
    }
    toast(result.message + '（' + result.costMs + 'ms）', result.success ? 'ok' : 'err');
    refreshStatus();
  }

  /** 在结果区展示错误横幅（连接中断等无完整步骤结果时使用） */
  function showErrorBanner(msg) {
    var banner = $('resultBanner');
    banner.style.display = 'block';
    banner.className = 'result-banner err';
    banner.textContent = '';
    banner.appendChild(document.createTextNode('❌ ' + msg));
    switchTab('result');
  }

  /** 连接中断时，把仍在“执行中”的步骤标记为失败，避免一直转圈 */
  function markStepsInterrupted() {
    taskSteps.forEach(function (s) {
      if (s && (s.status === 'RUNNING' || s.status === 'PENDING')) {
        s.status = 'FAIL';
        s.detail = s.detail || '操作中断，未收到后续进度';
        var li = $('step-' + s.index);
        if (li) {
          li.className = 'no';
          var ico = li.querySelector('.ico');
          if (ico) {
            ico.innerHTML = '✗';
          }
          var bar = li.querySelector('.progress');
          if (bar) {
            li.removeChild(bar);
          }
        }
      }
    });
  }

  // ------------------------------------------------------------ 实时进度（SSE）

  var taskSteps = [];

  function showProgressStart() {
    taskSteps = [];
    $('resultEmpty').style.display = 'none';
    $('resultBanner').style.display = 'none';
    $('uploadBox').style.display = 'none';
    switchTab('result');
    $('overallBarWrap').style.display = 'block';
    $('overallBar').style.width = '0%';
    $('overallText').textContent = '准备中...';
  }

  function renderSteps(steps) {
    taskSteps = steps.map(function (s) { return s; });
    $('stepList').innerHTML = taskSteps.map(stepHtml).join('');
    updateOverall();
  }

  function stepHtml(s) {
    var cls = s.status === 'RUNNING' ? 'running'
      : (s.status === 'SUCCESS' ? 'ok' : (s.status === 'FAIL' ? 'no' : 'pending'));
    var ico = s.status === 'RUNNING' ? '<span class="spinner"></span>'
      : (s.status === 'SUCCESS' ? '✓' : (s.status === 'FAIL' ? '✗' : '•'));
    // 上传类步骤的进度统一由顶部独立 uploadBox 展示，步骤内不再画重复进度条，避免视觉混乱
    var isUploadStep = isUploadStepName(s.name);
    var bar = (!isUploadStep && s.status === 'RUNNING' && s.progress > 0)
      ? '<div class="progress"><div class="progress-bar" style="width:' + s.progress + '%"></div>'
        + '<span class="progress-pct">' + s.progress + '%</span></div>' : '';
    return '<li id="step-' + s.index + '" class="' + cls + '">'
      + '<span class="ico">' + ico + '</span>'
      + '<span class="name">' + escapeHtml(s.name) + '</span>'
      + '<span class="detail">' + escapeHtml(s.detail || '') + '</span>'
      + bar
      + '</li>';
  }

  function updateStep(s) {
    taskSteps[s.index] = s;
    var li = $('step-' + s.index);
    if (!li) {
      return;
    }
    var cls = s.status === 'RUNNING' ? 'running'
      : (s.status === 'SUCCESS' ? 'ok' : (s.status === 'FAIL' ? 'no' : 'pending'));
    li.className = cls;
    li.querySelector('.ico').innerHTML = s.status === 'RUNNING' ? '<span class="spinner"></span>'
      : (s.status === 'SUCCESS' ? '✓' : (s.status === 'FAIL' ? '✗' : '•'));
    if (s.detail) {
      li.querySelector('.detail').textContent = s.detail;
    }
    var bar = li.querySelector('.progress');
    // 上传步骤的进度统一由顶部 uploadBox 展示，步骤内不画进度条
    if (s.status === 'RUNNING' && s.progress > 0 && !isUploadStepName(s.name)) {
      if (!bar) {
        bar = document.createElement('div');
        bar.className = 'progress';
        bar.innerHTML = '<div class="progress-bar"></div><span class="progress-pct"></span>';
        li.appendChild(bar);
      }
      bar.querySelector('.progress-bar').style.width = s.progress + '%';
      bar.querySelector('.progress-pct').textContent = s.progress + '%';
    } else if (bar) {
      li.removeChild(bar);
    }
    updateOverall();
  }

  function updateOverall() {
    var total = taskSteps.length;
    if (total === 0) {
      return;
    }
    var done = 0;
    for (var i = 0; i < total; i++) {
      var s = taskSteps[i];
      if (!s) {
        continue;
      }
      if (s.status === 'SUCCESS' || s.status === 'FAIL') {
        done += 1;
      } else if (s.status === 'RUNNING') {
        done += 0.5;
      }
    }
    var pct = Math.round(done / total * 100);
    $('overallBar').style.width = pct + '%';
    $('overallText').textContent = '总进度 ' + pct + '%';
  }

  /** 发起任务请求，拿到 taskId 后订阅 SSE 进度流。opts.stateKey 指定时进度写入该服务器状态而非全局 DOM */
  function runTask(url, init, opts) {
    return request(url, init).then(function (taskId) {
      return subscribeTask(taskId, opts);
    });
  }

  /**
   * 订阅 SSE：实时渲染每一步、上传百分比与速度，结束时渲染最终结果。
   * opts.stateKey 不为空时为「按服务器并行模式」：进度写入 serverDeployState[stateKey]，
   *   不写全局结果区（多台并行会互相覆盖），由 renderServerProgress() 按服务器展示。
   */
  function subscribeTask(taskId, opts) {
    opts = opts || {};
    var stateKey = opts.stateKey || null; // 非空=按服务器并行模式，进度写入该服务器状态
    return new Promise(function (resolve) {
      var es = new EventSource('/api/deploy/tasks/' + taskId + '/stream');
      var finished = false;

      // ---------------- 按服务器并行模式：只更新 serverDeployState，不碰全局结果区
      if (stateKey) {
        var st = serverDeployState[stateKey];
        if (st) { st.taskId = taskId; st.status = 'running'; }
        es.addEventListener('snapshot', function (ev) {
          try { var snap = JSON.parse(ev.data); serverProgressSnapshot(stateKey, snap.steps || []); } catch (e) { /* ignore */ }
        });
        es.addEventListener('step', function (ev) {
          try { serverProgressStep(stateKey, JSON.parse(ev.data)); } catch (e) { /* ignore */ }
        });
        es.addEventListener('done', function (ev) {
          if (finished) { return; }
          finished = true;
          es.close();
          var result = null;
          try { result = JSON.parse(ev.data); } catch (e) { /* ignore */ }
          serverProgressDone(stateKey, result);
          resolve(result && result.success !== false);
        });
        es.addEventListener('conn-error', function (ev) {
          if (finished) { return; }
          finished = true;
          es.close();
          var msg = '任务不存在或已过期，请重新执行操作';
          try { var d = JSON.parse(ev.data); if (d) { msg = d; } } catch (e) { /* ignore */ }
          serverProgressFail(stateKey, msg);
          resolve(false);
        });
        es.addEventListener('error', function (ev) {
          if (finished) { return; }
          finished = true;
          es.close();
          serverProgressFail(stateKey, '与服务器的连接中断，未收到执行结果。请检查服务是否存活后重试。');
          resolve(false);
        });
        return;
      }

      // ---------------- 默认模式（单任务）：渲染全局结果区
      showProgressStart();
      // 订阅开始时先隐藏独立上传进度条；仅当本次任务确实包含“上传 jar 包”步骤（上传 / 发布类）时才显示，
      // 回滚 / 启动 / 停止 / 重启等运维操作不含上传步骤，不应出现上传进度条。
      $('uploadBox').style.display = 'none';
      var taskHasUpload = false;
      es.addEventListener('snapshot', function (ev) {
        try {
          var snap = JSON.parse(ev.data);
          renderSteps(snap.steps);
          taskHasUpload = snap.steps.some(function (s) { return isUploadStepName(s.name); });
          if (taskHasUpload) {
            showUploadBox();
            var cur = parseInt($('uploadPct').textContent, 10);
            // 分片上传阶段可能已经显示 100%，此处不回退，避免“又从头传”的错觉
            if (isNaN(cur) || cur === 0) {
              updateUploadBar(0, '准备上传文件…', '');
            }
          } else {
            $('uploadBox').style.display = 'none';
          }
        } catch (e) { /* ignore */ }
      });
      es.addEventListener('step', function (ev) {
        try {
          var s = JSON.parse(ev.data);
          updateStep(s);
          // 上传类步骤：进入后端 SFTP 阶段（服务器→目标服务器）后，
          // 后端会通过 step 的 progress/speed/detail 推送真实传输进度、速度与已传大小，
          // 由 updateUploadServicing 实时渲染到独立 uploadBox（与后端 jar 包样式一致）。
          if (isUploadStepName(s.name) && s.status === 'RUNNING') {
            showUploadBox();
            updateUploadServicing(s.progress, s.speed, s.detail);
          } else if (isUploadStepName(s.name) && s.status === 'SUCCESS') {
            updateUploadBar(100, '上传完成', '');
            // 完成后稍作停留再隐藏，让用户看到 100%
            setTimeout(function () { $('uploadBox').style.display = 'none'; }, 600);
          } else if (isUploadStepName(s.name) && s.status === 'FAIL') {
            $('uploadBox').style.display = 'none';
          }
        } catch (e) { /* ignore */ }
      });
      es.addEventListener('done', function (ev) {
        finished = true;
        $('uploadBox').style.display = 'none';
        try { renderResult(JSON.parse(ev.data)); } catch (e) { /* ignore */ }
        es.close();
        resolve(true);
      });
      // 连接级错误（任务不存在 / 刷新后 taskId 失效）
      es.addEventListener('conn-error', function (ev) {
        if (finished) { return; }
        finished = true;
        es.close();
        var msg = '任务不存在或已过期，请重新执行操作';
        try { var d = JSON.parse(ev.data); if (d) { msg = d; } } catch (e) { /* ignore */ }
        markStepsInterrupted();
        showErrorBanner(msg);
        toast(msg, 'err');
        $('uploadBox').style.display = 'none';
        resolve(false);
      });
      // 原生连接错误（如网络断开）：兜底提示
      es.addEventListener('error', function (ev) {
        if (finished) { return; }
        finished = true;
        es.close();
        var msg;
        try { msg = ev.data ? JSON.parse(ev.data) : null; } catch (e) { msg = null; }
        if (!msg) { msg = '与服务器的连接中断，未收到执行结果。请检查服务是否存活后重试。'; }
        markStepsInterrupted();
        showErrorBanner(msg);
        toast(msg, 'err');
        $('uploadBox').style.display = 'none';
        resolve(false);
      });
    });
  }

  /** ---- 按服务器并行发布：进度写入 serverDeployState 并刷新对应视图 ---- */
  function serverProgressSnapshot(serverId, steps) {
    var st = serverDeployState[serverId];
    if (!st) { return; }
    st.steps = steps.map(function (s) { return s; });
    refreshServerProgress(serverId);
  }

  function serverProgressStep(serverId, s) {
    var st = serverDeployState[serverId];
    if (!st) { return; }
    st.steps[s.index] = s;
    refreshServerProgress(serverId);
  }

  function serverProgressDone(serverId, result) {
    var st = serverDeployState[serverId];
    if (!st) { return; }
    st.status = result && result.success !== false ? 'success' : 'fail';
    st.finishCount++;
    if (result && result.message) { st.detail = result.message; }
    if (st.steps && st.steps.length) {
      st.steps.forEach(function (s) { if (s.status === 'RUNNING') { s.status = 'SUCCESS'; } });
    }
    refreshServerProgress(serverId);
  }

  function serverProgressFail(serverId, msg) {
    var st = serverDeployState[serverId];
    if (!st) { return; }
    st.status = 'fail';
    st.finishCount++;
    st.detail = msg;
    refreshServerProgress(serverId);
  }

  /** 计算某服务器当前任务的完成百分比 */
  function serverProgressPct(st) {
    var steps = (st && st.steps) || [];
    var total = steps.length;
    if (!total) { return 0; }
    var done = 0;
    for (var i = 0; i < total; i++) {
      var s = steps[i];
      if (!s) { continue; }
      if (s.status === 'SUCCESS' || s.status === 'FAIL') { done += 1; }
      else if (s.status === 'RUNNING') { done += 0.5; }
    }
    return Math.round(done / total * 100);
  }

  /**
   * 刷新某服务器的进度展示：
   * - 左侧服务器列表项的状态徽章（始终更新）
   * - 若当前选中的正是该服务器，把进度渲染到执行结果区（点击服务器即可看到）
   */
  function refreshServerProgress(serverId) {
    var st = serverDeployState[serverId];
    if (!st) { return; }
    var pct = serverProgressPct(st);
    // 左侧列表徽章
    var badge = $('svrDeployBadge-' + serverId);
    if (badge) {
      if (st.status === 'running') {
        badge.style.display = '';
        badge.className = 'svr-deploy-badge running';
        badge.textContent = '发布中 ' + pct + '%';
      } else if (st.status === 'success') {
        badge.style.display = '';
        badge.className = 'svr-deploy-badge success';
        badge.textContent = '✓ 完成';
      } else if (st.status === 'fail') {
        badge.style.display = '';
        badge.className = 'svr-deploy-badge fail';
        badge.textContent = '✗ 失败';
      } else {
        badge.style.display = 'none';
      }
    }
    // 当前选中的服务器：渲染其进度到结果区
    if (current && current.id === serverId) {
      renderServerProgressPanel(serverId);
    }
  }

  /** 把某台服务器的发布进度渲染到执行结果区（步骤列表 + 总进度条 + 状态条） */
  function renderServerProgressPanel(serverId) {
    var st = serverDeployState[serverId];
    if (!st) { return; }
    $('resultEmpty').style.display = 'none';
    $('resultBanner').style.display = 'none';
    $('uploadBox').style.display = 'none';
    $('overallBarWrap').style.display = 'block';
    var pct = serverProgressPct(st);
    $('overallBar').style.width = pct + '%';
    $('overallText').textContent = '总进度 ' + pct + '%';

    var steps = st.steps || [];
    $('stepList').innerHTML = steps.map(function (s) {
      var cls = s.status === 'RUNNING' ? 'running'
        : (s.status === 'SUCCESS' ? 'ok' : (s.status === 'FAIL' ? 'no' : 'pending'));
      var ico = s.status === 'RUNNING' ? '<span class="spinner"></span>'
        : (s.status === 'SUCCESS' ? '✓' : (s.status === 'FAIL' ? '✗' : '•'));
      var isUploadStep = isUploadStepName(s.name);
      var bar = (!isUploadStep && s.status === 'RUNNING' && s.progress > 0)
        ? '<div class="progress"><div class="progress-bar" style="width:' + s.progress + '%"></div>'
          + '<span class="progress-pct">' + s.progress + '%</span></div>' : '';
      return '<li class="' + cls + '"><span class="ico">' + ico + '</span>'
        + '<span class="name">' + escapeHtml(s.name) + '</span>'
        + '<span class="detail">' + escapeHtml(s.detail || '') + '</span>' + bar + '</li>';
    }).join('');

    var svrName = (servers.filter(function (x) { return x.id === serverId; })[0] || {}).name || serverId;
    var label = st.status === 'running' ? '发布中' : (st.status === 'success' ? '发布完成' : (st.status === 'fail' ? '发布失败' : '就绪'));
    var head = '【' + escapeHtml(svrName) + '】' + label + '：' + escapeHtml(st.taskName || '')
      + '（该服务器 ' + st.finishCount + '/' + st.total + ' 个配置）';
    showSeqStatus(head + (st.detail ? '\n' + st.detail : ''),
      st.status === 'success' ? 'ok' : (st.status === 'fail' ? 'err' : 'run'));
    switchTab('result');
  }

  /** 上传 jar 并发布到当前选中的发布配置（分片 + 断点续传） */
  function uploadDeploy(file) {
    if (!current || !currentTarget) {
      toast('请先选择服务器并配置发布目标', 'err');
      return;
    }
    if (!file) {
      toast('请先选择 jar 文件', 'err');
      return;
    }
    var restart = $('restartAfterUpload').checked ? 'true' : 'false';
    chunkedUploadDeploy(file, currentTarget.id, restart);
  }

  /**
   * 按文件数量选择上传方式：
   * 单个文件走分片上传（支持断点续传），多个文件走批量上传接口（一次传输、只重启一次）。
   */
  /** 上传 conf 文件到发布配置的 conf 目录（保存配置后自动执行，同名覆盖、不重启应用） */
  function uploadConfFiles(files, targetId) {
    if (!files || !files.length || !targetId) {
      return Promise.resolve();
    }
    var fd = new FormData();
    files.forEach(function (f) { fd.append('files', f, f.name); });
    return xhrUpload('/api/deploy/' + targetId + '/conf-upload', fd, null).then(function (data) {
      var dir = data && data.confDir ? data.confDir : 'conf 目录';
      var names = data && data.files && data.files.length ? data.files.join('、') : '';
      toast('conf 已更新到 ' + dir + (names ? '：' + names : ''), 'ok');
    }).catch(function (e) {
      toast('conf 上传失败：' + (e && e.message ? e.message : e), 'err');
    });
  }

  function uploadPickedFiles(files, targetId, restart) {
    if (!files || !files.length) {
      return;
    }
    if (files.length === 1) {
      chunkedUploadDeploy(files[0], targetId, restart);
      return;
    }
    multiUploadDeploy(files, targetId, restart);
  }

  /**
   * 多 jar 统一上传更新（自定义部署）：一次请求把所选 jar 全部提交到服务端，
   * 服务端逐个传输到发布目录后只停止 / 启动一次。
   */
  function multiUploadDeploy(files, targetId, restart) {
    var fd = new FormData();
    files.forEach(function (f) { fd.append('files', f, f.name); });
    fd.append('restart', restart);
    showUploadBox();
    updateUploadBar(0, '准备上传 ' + files.length + ' 个 jar 包…', '');
    xhrUpload('/api/deploy/' + targetId + '/upload-multi', fd, function (pct, loaded, total, speed) {
      updateUploadBar(pct, '上传中 ' + pct + '%（' + files.length + ' 个 jar 包）', formatSize(speed) + '/s');
    }).then(function (taskId) {
      updateUploadBar(100, '文件已上传，正在发布…', '');
      return subscribeTask(taskId);
    }).catch(function (e) {
      $('uploadBox').style.display = 'none';
      toast(e && e.message || '上传失败', 'err');
    });
  }

  /** 一键发布：选择一个本地 jar 后，自动完成「上传 → 备份 → 停止 → 启动」整套流程（始终重启，分片 + 断点续传） */
  function oneDeployDeploy(targetId, file) {
    if (!current) {
      toast('请先选择服务器', 'err');
      return;
    }
    if (!file) {
      toast('请先选择 jar 文件', 'err');
      return;
    }
    chunkedUploadDeploy(file, targetId, 'true');
  }

  // ===================== 浏览器目录句柄上传（免选文件） =====================
  // 适用：一键发布时本机路径不存在，或完全未配置本机路径。用 File System Access API
  // 让用户授权一次本地目录，之后按 jar 名称自动定位文件上传，无需每次手动选文件。
  // 目录句柄通过 IndexedDB 持久化；Safari / Firefox 不支持时退回普通文件选择。
  var DIR_HANDLE_KEY = 'deployManagerDirHandle';
  var cachedDirHandle = null;

  function fsAccessSupported() {
    return typeof window !== 'undefined' && typeof window.showDirectoryPicker === 'function';
  }

  function basename(p) {
    return (p || '').replace(/^.*[\\\/]/, '');
  }

  function openDirDb() {
    return new Promise(function (resolve) {
      if (!('indexedDB' in window)) { resolve(null); return; }
      var req = indexedDB.open('deployManagerFS', 1);
      req.onupgradeneeded = function () { req.result.createObjectStore('kv'); };
      req.onsuccess = function () { resolve(req.result); };
      req.onerror = function () { resolve(null); };
    });
  }

  function saveDirHandle(h) {
    cachedDirHandle = h;
    return openDirDb().then(function (db) {
      return new Promise(function (resolve) {
        if (!db) { resolve(); return; }
        try {
          var tx = db.transaction('kv', 'readwrite');
          tx.objectStore('kv').put(h, DIR_HANDLE_KEY);
          tx.oncomplete = function () { db.close(); resolve(); };
          tx.onerror = function () { db.close(); resolve(); };
        } catch (e) { db.close(); resolve(); }
      });
    });
  }

  function loadDirHandle() {
    if (cachedDirHandle) { return Promise.resolve(cachedDirHandle); }
    return openDirDb().then(function (db) {
      return new Promise(function (resolve) {
        if (!db) { resolve(null); return; }
        try {
          var tx = db.transaction('kv', 'readonly');
          var g = tx.objectStore('kv').get(DIR_HANDLE_KEY);
          g.onsuccess = function () { db.close(); cachedDirHandle = g.result || null; resolve(cachedDirHandle); };
          g.onerror = function () { db.close(); resolve(null); };
        } catch (e) { db.close(); resolve(null); }
      });
    });
  }

  function clearDirHandle() {
    cachedDirHandle = null;
    return openDirDb().then(function (db) {
      if (!db) { return; }
      try {
        var tx = db.transaction('kv', 'readwrite');
        tx.objectStore('kv').delete(DIR_HANDLE_KEY);
        tx.oncomplete = function () { db.close(); };
        tx.onerror = function () { db.close(); };
      } catch (e) { db.close(); }
    });
  }

  /** 弹出目录选择弹窗（必须在用户点击手势内触发），用户选择后持久化并返回目录句柄 */
  function showDirPickModal() {
    return new Promise(function (resolve, reject) {
      if (!fsAccessSupported()) { reject(new Error('NO_SUPPORT')); return; }
      var mask = document.createElement('div');
      mask.className = 'modal-mask';
      mask.style.cssText = 'position:fixed;inset:0;background:rgba(0,0,0,.45);display:flex;'
        + 'align-items:center;justify-content:center;z-index:9999';
      mask.innerHTML = '<div style="background:#1e2430;color:#e6e9ef;padding:22px 24px;border-radius:10px;'
        + 'max-width:440px;width:90%">'
        + '<div style="font-size:15px;font-weight:600;margin-bottom:10px">选择发布包所在目录</div>'
        + '<div style="font-size:13px;line-height:1.6;color:#aab2c0;margin-bottom:16px">'
        + '本机路径不存在，请选择包含 jar 包的本地目录。授权后会被记住，'
        + '下次一键发布按配置中的 jar 名称自动查找，无需再选文件。</div>'
        + '<div style="text-align:right">'
        + '<button id="dirPickCancel" style="margin-right:8px;padding:7px 14px;border:none;border-radius:6px;'
        + 'background:#333b4a;color:#e6e9ef;cursor:pointer">取消</button>'
        + '<button id="dirPickOk" style="padding:7px 14px;border:none;border-radius:6px;'
        + 'background:#2f7de0;color:#fff;cursor:pointer">选择目录</button>'
        + '</div></div>';
      document.body.appendChild(mask);
      function close() { if (mask.parentNode) { mask.parentNode.removeChild(mask); } }
      $('dirPickCancel').onclick = function () { close(); reject(new Error('已取消')); };
      $('dirPickOk').onclick = function () {
        window.showDirectoryPicker().then(function (h) {
          saveDirHandle(h).then(function () { close(); resolve(h); });
        }).catch(function (e) { close(); reject(e); });
      };
    });
  }

  /** 取已持久化的目录句柄，无则弹出选择弹窗 */
  function ensureDirHandle() {
    return loadDirHandle().then(function (h) {
      if (h) { return h; }
      return showDirPickModal();
    });
  }

  /** 在目录句柄中按文件名（忽略大小写）递归查找文件，返回 FileSystemFileHandle 或 null */
  function findFileInDir(dirHandle, fileName) {
    var lower = (fileName || '').toLowerCase();
    if (!lower) { return Promise.resolve(null); }
    var walk = function (handle) {
      return handle.values().next().then(function step(item) {
        if (item.done) { return null; }
        var entry = item.value;
        if (entry.kind === 'file') {
          if (entry.name.toLowerCase() === lower) { return entry; }
        } else if (entry.kind === 'directory') {
          return walk(entry).then(function (r) {
            if (r) { return r; }
            return handle.values().next().then(step);
          });
        }
        return handle.values().next().then(step);
      });
    };
    return walk(dirHandle);
  }

  /** 不支持目录句柄时，退回普通文件选择框（自定义部署后端可多选 jar） */
  function fallbackFilePicker(target) {
    if (target.type === 'frontend') {
      var fe = $('feUploadFile');
      fe.value = '';
      fe.click();
    } else {
      var od = $('oneDeployJar');
      od.value = '';
      od.multiple = target.deployMode === 'custom';
      od.dataset.target = target.id;
      od.click();
    }
  }

  /**
   * 一键发布回退：通过浏览器目录句柄定位并上传发布包（免选文件）。
   * 先取持久化目录句柄（无则弹窗授权），按 jar 名称查找文件后走分片上传发布。
   */
  function uploadViaDirHandle(target) {
    if (!current || !target) { return; }
    // 自定义部署可能包含多个 jar，按名在目录里定位不可靠，直接打开（可多选）文件选择框
    if (isCustomBackend(target)) { fallbackFilePicker(target); return; }
    if (!fsAccessSupported()) { fallbackFilePicker(target); return; }
    var wantName = target.type === 'frontend'
      ? (target.frontendPath ? basename(target.frontendPath) : null)
      : target.jarName;
    ensureDirHandle().then(function (dirHandle) {
      return findFileInDir(dirHandle, wantName);
    }).then(function (fileHandle) {
      if (!fileHandle) {
        toast('所选目录中未找到「' + (wantName || '文件') + '」，请手动选择或检查配置名称', 'err');
        fallbackFilePicker(target);
        return;
      }
      return fileHandle.getFile().then(function (file) {
        chunkedUploadDeploy(file, target.id, 'true');
      });
    }).catch(function (e) {
      var m = e && e.message || '';
      if (m === '已取消') { return; }
      if (/NO_SUPPORT|permission|SecurityError|gesture/i.test(m)) {
        clearDirHandle();
        fallbackFilePicker(target);
        return;
      }
      toast(m || '未选择目录', 'err');
    });
  }

  /**
   * 分片上传 jar 并发布，支持断点续传。
   * 流程：
   *  1) 生成 uploadId，先查服务端已收分片（断点）；
   *  2) 逐片上传缺失分片，独立进度条显示整体进度与速度；
   *  3) 全部到齐后 finish，服务端合并并触发“本机路径发布”（SFTP + 备份 + 重启），返回 taskId；
   *  4) 交给 subscribeTask 显示后台发布进度。
   */
  function chunkedUploadDeploy(file, targetId, restart) {
    var CHUNK = 5 * 1024 * 1024; // 5MB 一片
    var total = file.size;
    var totalChunks = Math.max(1, Math.ceil(total / CHUNK));
    // uploadId 由“文件名 + 大小”稳定生成，同一文件每次上传复用同一分片目录，
    // 因此中断后重新选择同一文件即可自动跳过已传分片（断点续传）。
    var uploadId = 'u' + (file.name || 'app') + '-' + total;
    var fileName = file.name;

    showUploadBox();
    updateUploadBar(0, '准备分片上传（共 ' + totalChunks + ' 片，支持断点续传）…', '');

    // 先查询断点：服务端已存在的分片直接跳过
    fetch('/api/deploy/' + targetId + '/chunk?action=status&uploadId=' + encodeURIComponent(uploadId))
      .then(function (r) { return r.json(); })
      .then(function (j) {
        var done = {};
        if (j.code === 0 && Array.isArray(j.data)) {
          j.data.forEach(function (i) { done[i] = true; });
        }
        return uploadMissingChunks(file, targetId, uploadId, fileName, totalChunks, CHUNK, done, restart);
      })
      .catch(function (e) {
        $('uploadBox').style.display = 'none';
        toast(e && e.message || '上传失败', 'err');
      });
  }

  /** 逐片上传缺失分片，全部完成后发起 finish 并订阅发布进度。
   *  每片失败自动重试（最多 3 次，退避 1s），避免链路抖动导致整体上传失败。 */
  function uploadMissingChunks(file, targetId, uploadId, fileName, totalChunks, CHUNK, done, restart) {
    var index = 0;
    // 已传字节基数（用于整体进度：基于已传字节 / 总字节，避免最后一片卡 99%）
    var baseSent = 0;
    for (var i = 0; i < totalChunks; i++) {
      if (done[i]) {
        baseSent += Math.min(CHUNK, file.size - i * CHUNK);
      }
    }
    // 上传单片；retries 为剩余重试次数，网络错误/超时自动重试
    function sendChunk(cur, retries) {
      var start = cur * CHUNK;
      var end = Math.min(start + CHUNK, file.size);
      var blob = file.slice(start, end);
      var fd = new FormData();
      fd.append('file', blob, fileName);
      fd.append('action', 'upload');
      fd.append('uploadId', uploadId);
      fd.append('chunkIndex', String(cur));
      fd.append('totalChunks', String(totalChunks));
      return xhrUpload('/api/deploy/' + targetId + '/chunk', fd, function (pct, loaded, cTotal, speed) {
        var sent = baseSent + loaded;
        var overall = Math.min(100, Math.round(sent / file.size * 100));
        var tip = '上传中 ' + overall + '%（第 ' + (cur + 1) + '/' + totalChunks + ' 片'
          + (retries < 3 ? '，重试中' : '') + '）';
        updateUploadBar(overall, tip, formatSize(speed) + '/s');
      }).catch(function (e) {
        if (retries > 0) {
          updateUploadBar(0, '第 ' + (cur + 1) + ' 片上传失败，正在重试（剩 ' + retries + ' 次）…', '');
          return new Promise(function (r) { setTimeout(r, 1000); })
            .then(function () { return sendChunk(cur, retries - 1); });
        }
        throw e;
      });
    }
    function next() {
      // 跳过已存在的分片（断点续传）
      while (index < totalChunks && done[index]) {
        index++;
      }
      if (index >= totalChunks) {
        finishChunkedUpload(targetId, uploadId, fileName, totalChunks, restart);
        return;
      }
      var cur = index;
      sendChunk(cur, 3).then(function () {
        baseSent += Math.min(CHUNK, file.size - cur * CHUNK);
        done[cur] = true;
        index++;
        next();
      }).catch(function (e) {
        $('uploadBox').style.display = 'none';
        toast((e && e.message || '分片上传失败') + '（第 ' + (cur + 1) + ' 片）', 'err');
      });
    }
    next();
  }

  /** 合并分片并触发发布，返回 taskId 后交给 SSE 进度 */
  function finishChunkedUpload(targetId, uploadId, fileName, totalChunks, restart) {
    updateUploadBar(100, '分片已全部上传，正在合并并发布…', '');
    var fd = new FormData();
    fd.append('action', 'finish');
    fd.append('uploadId', uploadId);
    fd.append('totalChunks', String(totalChunks));
    fd.append('fileName', fileName);
    fd.append('restart', restart);
    request('/api/deploy/' + targetId + '/chunk', { method: 'POST', body: fd })
      .then(function (taskId) {
        // 交由 subscribeTask 无缝接管为后端 SFTP/备份/重启进度
        return subscribeTask(taskId);
      })
      .catch(function (e) {
        $('uploadBox').style.display = 'none';
        toast(e && e.message || '发布失败', 'err');
      });
  }

  /** 通过 XHR 上传（带浏览器→服务器的真实进度回调 onProgress(pct,loaded,total,speed)），resolve 服务端返回的 data */
  function xhrUpload(url, fd, onProgress) {
    return new Promise(function (resolve, reject) {
      var xhr = new XMLHttpRequest();
      xhr.open('POST', url);
      // 卡死的连接（链路抖动/服务端无响应）在 5 分钟后判失败，交给上层重试，避免无限转圈
      xhr.timeout = 300000;
      var t0 = Date.now();
      if (onProgress && xhr.upload) {
        xhr.upload.onprogress = function (e) {
          if (e.lengthComputable && e.total > 0) {
            var pct = Math.round(e.loaded / e.total * 100);
            var dt = (Date.now() - t0) / 1000;
            var speed = dt > 0 ? e.loaded / dt : 0;
            onProgress(pct, e.loaded, e.total, speed);
          }
        };
      }
      xhr.onload = function () {
        try {
          var json = JSON.parse(xhr.responseText);
          if (json.code !== 0) {
            reject(new Error(json.message || '上传失败'));
            return;
          }
          resolve(json.data);
        } catch (err) {
          reject(new Error('响应解析失败'));
        }
      };
      xhr.onerror = function () { reject(new Error('网络错误，上传失败')); };
      xhr.ontimeout = function () { reject(new Error('上传超时，连接中断')); };
      xhr.send(fd);
    });
  }

  function showUploadBox() {
    $('resultEmpty').style.display = 'none';
    switchTab('result');
    $('uploadBox').style.display = 'block';
  }

  function updateUploadBar(pct, text, speed) {
    $('uploadBar').style.width = pct + '%';
    $('uploadPct').textContent = pct + '%';
    $('uploadSpeed').textContent = (speed == null || speed === '') ? '' : speed;
    if (text) {
      $('uploadText').textContent = text;
    }
  }

  /**
   * 后端 SFTP 阶段（服务器→目标服务器）：根据后端推送的真实数据刷新 uploadBox。
   * @param {number} pct    已传输百分比 0~100
   * @param {number} speed  已传输速度（字节/秒），0 表示未知
   * @param {string} detail 后端推送的说明文字（含已传大小/总大小）
   */
  function updateUploadServicing(pct, speed, detail) {
    var p = (typeof pct === 'number' && !isNaN(pct)) ? pct : 0;
    $('uploadBar').style.width = p + '%';
    $('uploadPct').textContent = p + '%';
    var spd = (typeof speed === 'number' && speed > 0) ? formatSize(speed) + '/s' : '';
    $('uploadSpeed').textContent = spd;
    if (detail) {
      $('uploadText').textContent = detail;
    }
  }

  /** 是否为“上传文件”类步骤（上传 jar 包 / 上传静态资源 / 压缩并上传静态资源）。
   *  此类步骤的进度统一由顶部独立 uploadBox 展示（与后端 jar 包样式一致）。 */
  function isUploadStepName(name) {
    return name === '上传 jar 包' || name === '上传静态资源' || name === '压缩并上传静态资源'
      || name === '上传 JDK 安装包';
  }

  function formatSize(bytes) {
    if (bytes === 0) {
      return '0B';
    }
    if (!bytes || bytes < 0) {
      return '-';
    }
    if (bytes < 1024) {
      return bytes + 'B';
    }
    var kb = bytes / 1024;
    if (kb < 1024) {
      return kb.toFixed(1) + 'KB';
    }
    var mb = kb / 1024;
    if (mb < 1024) {
      return mb.toFixed(1) + 'MB';
    }
    return (mb / 1024).toFixed(2) + 'GB';
  }

  /** 前端静态资源发布：读取配置中的本机发布路径，按是否 zip 上传并执行发布后命令 */
  function frontendDeploy(target, btn) {
    if (!current || !target) {
      return;
    }
    busy(btn, true);
    runTask('/api/deploy/' + target.id + '/frontend', { method: 'POST' })
      .then(function () { busy(btn, false); })
      .catch(function (e) { toast(e && e.message || '发布失败', 'err'); busy(btn, false); });
  }

  /** 手动备份：后端类型备份 jar 包，前端类型把发布目录打包成 tar.gz（异步任务） */
  function manualBackup(target, btn) {
    if (!current || !target) {
      return;
    }
    busy(btn, true);
    runTask('/api/deploy/' + target.id + '/backup', { method: 'POST' })
      .then(function () { busy(btn, false); })
      .catch(function (e) { toast(e && e.message || '备份失败', 'err'); busy(btn, false); });
  }

  /** 用本机已有的 jar 路径发布到指定发布配置（路径 / 是否重启来自编辑弹窗内的输入） */
  function localDeploy(target, path, restart, btn) {
    if (!current || !target) {
      return;
    }
    if (!path) {
      toast('请填写本机 jar 路径', 'err');
      return;
    }
    busy(btn, true);
    var url = '/api/deploy/' + target.id + '/local?path=' + encodeURIComponent(path)
      + '&restart=' + (restart ? 'true' : 'false');
    runTask(url, { method: 'POST' })
      .then(function () { busy(btn, false); })
      .catch(function (e) {
        var msg = e && e.message || '发布失败';
        // 本机路径不存在时，改用浏览器目录句柄上传（免选文件）；不支持的浏览器退回普通文件选择
        if (/本机 jar 包不存在|本机发布路径不存在/.test(msg)) {
          toast('本机路径不存在，改用浏览器选择目录上传', 'ok');
          busy(btn, false);
          uploadViaDirHandle(target);
          return;
        }
        toast(msg, 'err');
        busy(btn, false);
      });
  }

  /**
   * 发布单个配置并返回 Promise（任务完成时 resolve）。
   * - 前端类型：按本机发布路径发布；
   * - 后端类型且已配置本机路径：按本机路径发布；
   * - 后端类型但未配置本机路径：无法自动发布（需手动选 jar），reject 跳过。
   * 用于「按顺序一键发布」的顺序串联。
   */
  function deployTarget(t, opts) {
    var run = function (url) {
      // runTask 失败也会 resolve(false)，这里统一转成 reject，便于并行/顺序发布区分成功/失败
      return runTask(url, { method: 'POST' }, opts).then(function (ok) {
        if (!ok) {
          throw new Error('发布未成功，请查看执行结果');
        }
      });
    };
    if (t.type === 'frontend') {
      return run('/api/deploy/' + t.id + '/frontend');
    }
    if (t.deployMode === 'custom') {
      return Promise.reject(new Error('「' + (t.name || '未命名') + '」为自定义部署，需手动选择 jar 包发布，已跳过'));
    }
    if (t.localPath && t.localPath.trim()) {
      var restart = t.restartLocal !== false;
      var url = '/api/deploy/' + t.id + '/local?path=' + encodeURIComponent(t.localPath.trim())
        + '&restart=' + (restart ? 'true' : 'false');
      return run(url);
    }
    return Promise.reject(new Error('「' + (t.name || '未命名') + '」未配置本机路径，需手动选择 jar 发布，已跳过'));
  }

  /** 并行一键发布：跨服务器勾选的配置同时发布；每台服务器内部按勾选顺序串行，服务器之间并行 */
  function deploySequential() {
    if (sequentialDeploying) {
      toast('已有一键发布任务在执行，请等待完成', 'err');
      return;
    }
    // 收集所有勾选的 target 详情（跨服务器）
    var picked = [];
    for (var id in selectedTargetIds) {
      if (selectedTargetIds.hasOwnProperty(id) && selectedTargetIds[id] && selectedTargetMap[id]) {
        picked.push(selectedTargetMap[id]);
      }
    }
    if (!picked.length) {
      toast('请先勾选要发布的配置', 'err');
      return;
    }
    // 按 serverId 分组，保持各服务器内的勾选顺序
    var groupsByServer = {};
    var serverOrder = [];
    picked.forEach(function (t) {
      var sid = t.serverId || (current && current.id);
      if (!sid) { return; }
      if (!groupsByServer[sid]) { groupsByServer[sid] = []; serverOrder.push(sid); }
      groupsByServer[sid].push(t);
    });
    if (!serverOrder.length) {
      toast('勾选的配置缺少所属服务器信息', 'err');
      return;
    }

    sequentialDeploying = true;
    var btn = $('btnDeploySequential');
    busy(btn, true);
    updateSeqBtn();
    switchTab('result');
    var totalServers = serverOrder.length;
    var totalTargets = picked.length;
    toast('开始并行发布：' + totalServers + ' 台服务器 / ' + totalTargets + ' 个配置', 'ok');

    // 为每台服务器初始化进度状态
    serverOrder.forEach(function (sid) {
      var ts = groupsByServer[sid];
      serverDeployState[sid] = {
        taskId: null,
        taskName: ts[0].name || '未命名',
        steps: [],
        status: 'running',
        detail: '',
        finishCount: 0,
        total: ts.length,
        serverId: sid
      };
      refreshServerProgress(sid);
    });

    var failCount = 0;
    var doneServers = 0;

    // 每台服务器一条流水线：内部串行、彼此并行
    var serverPromises = serverOrder.map(function (sid) {
      var ts = groupsByServer[sid];
      var chain = Promise.resolve();
      ts.forEach(function (t) {
        chain = chain.then(function () {
          var st = serverDeployState[sid];
          if (st) {
            st.taskName = t.name || '未命名';
            st.steps = [];
            st.detail = '';
            st.status = 'running'; // 该服务器进入下一个配置，重新显示"发布中"
            refreshServerProgress(sid);
          }
          return deployTarget(t, { stateKey: sid }).catch(function (e) {
            failCount++;
            toast('✗ 失败：' + (t.name || '未命名') + ' - ' + (e && e.message ? e.message : '未知错误'), 'err');
          });
        });
      });
      return chain.then(function () {
        doneServers++;
        var st = serverDeployState[sid];
        if (st && st.status === 'running') {
          st.status = st.finishCount === st.total ? 'success' : 'fail';
        }
        refreshServerProgress(sid);
      });
    });

    Promise.all(serverPromises).then(function () {
      sequentialDeploying = false;
      busy(btn, false);
      selectedTargetIds = {};
      selectedTargetMap = {};
      renderTargets();
      var okCount = totalTargets - failCount;
      var summary = '并行发布完成：成功 ' + okCount + '，失败 ' + failCount
        + '（' + totalServers + ' 台服务器 / ' + totalTargets + ' 个配置）';
      showSeqStatus(summary, failCount ? 'err' : 'ok');
      toast(summary, failCount ? 'err' : 'ok');
    });
  }

  /** 对指定发布配置执行 启动 / 停止 / 重启，返回 Promise 便于批量串联 */
  function appAction(target, action, btn) {
    if (!current || !target) {
      return Promise.resolve(false);
    }
    busy(btn, true);
    return runTask('/api/apps/' + target.id + '/' + action, { method: 'POST' })
      .then(function () { busy(btn, false); return true; })
      .catch(function (e) { toast(e && e.message || '操作失败', 'err'); busy(btn, false); return false; });
  }

  /** 批量重启：按当前列表顺序，对勾选的（后端）发布配置逐个重启（一个完成再下一个） */
  function restartBatch() {
    if (!current) {
      toast('请先选择一台服务器', 'err');
      return;
    }
    if (sequentialDeploying || batchRestarting) {
      toast('已有批量任务在执行，请等待完成', 'err');
      return;
    }
    var ids = [];
    for (var k in selectedTargetIds) {
      if (selectedTargetIds.hasOwnProperty(k) && selectedTargetIds[k]) {
        ids.push(k);
      }
    }
    // 按当前展示顺序（受拖拽排序影响）组织队列
    var queue = targets.filter(function (t) { return ids.indexOf(t.id) >= 0; });
    // 仅后端类型支持进程重启，前端类型无 jar 进程，自动跳过
    var be = queue.filter(function (t) { return t.type !== 'frontend'; });
    if (!be.length) {
      toast('勾选的配置均为前端类型，无进程可重启', 'err');
      return;
    }
    var feSkipped = queue.length - be.length;
    batchRestarting = true;
    var btn = $('btnRestartBatch');
    busy(btn, true);
    updateSeqBtn();
    switchTab('result');
    if (feSkipped > 0) {
      toast('已跳过 ' + feSkipped + ' 个前端配置（无进程），仅重启后端', 'ok');
    }
    var i = 0;
    var failCount = 0;
    function next() {
      if (i >= be.length) {
        batchRestarting = false;
        busy(btn, false);
        showSeqStatus('批量重启已完成（成功 ' + (be.length - failCount)
          + '，失败 ' + failCount + '，共 ' + be.length + ' 个后端配置）', failCount ? 'err' : 'ok');
        toast('全部重启任务已完成', 'ok');
        return;
      }
      var t = be[i];
      i++;
      currentTarget = t;
      renderTargets();
      renderPanel();
      showSeqStatus('正在重启（' + i + '/' + be.length + '）：' + (t.name || '未命名')
        + '\n' + (t.remoteDir || '') + '/' + (t.jarName || ''), 'run');
      toast('正在重启（' + i + '/' + be.length + '）：' + (t.name || '未命名'), 'ok');
      appAction(t, 'restart', null).then(function (ok) {
        if (!ok) {
          failCount++;
        }
        toast('✓ 已重启：' + (t.name || '未命名'), 'ok');
        next();
      }).catch(function (e) {
        failCount++;
        toast('✗ 重启失败：' + (e && e.message ? e.message : '未知错误'), 'err');
        next();
      });
    }
    next();
  }

  function testConnect(btn) {
    if (!current) {
      return;
    }
    busy(btn, true);
    post('/api/servers/' + current.id + '/test').then(function (data) {
      if (data.success) {
        toast('连接成功', 'ok');
        $('resultEmpty').style.display = 'none';
        switchTab('result');
        $('stepList').innerHTML = '<li><span class="ico ok">✓</span><span class="name">登录信息</span>'
          + '<span class="detail">' + escapeHtml(data.login) + '</span></li>'
          + (data.suEnabled ? '<li><span class="' + (data.su && data.su.indexOf('失败') < 0 ? 'ico ok' : 'ico no') + '">'
            + (data.su && data.su.indexOf('失败') < 0 ? '✓' : '✗') + '</span><span class="name">su 切换（→' + escapeHtml(data.suUser || 'root') + '）</span>'
            + '<span class="detail">' + escapeHtml(data.su || '') + '</span></li>' : '')
          + '<li><span class="ico ok">✓</span><span class="name">java 版本</span>'
          + '<span class="detail">' + escapeHtml(data.java) + '</span></li>'
          + '<li><span class="ico ok">✓</span><span class="name">发布目录</span>'
          + '<span class="detail">' + escapeHtml(data.remoteDir) + '</span></li>';
      } else {
        toast('连接失败: ' + data.message, 'err');
      }
    }).catch(function (e) {
      toast(e.message, 'err');
    }).then(function () {
      busy(btn, false);
    });
  }

  // ------------------------------------------------------------ 安装 JDK

  function openJdkModal() {
    if (!current) {
      toast('请先选择一台服务器', 'err');
      return;
    }
    $('jdkFile').value = '';
    $('jdkInstallDir').value = '/usr/local/java';
    $('jdkLinkName').value = '';
    $('jdkConfigureEnv').checked = true;
    $('jdkVerHint').textContent = '';
    $('jdkModalMask').classList.add('show');
  }

  /** 从 JDK 文件名粗略识别版本，便于上传前确认「指定版本」 */
  function parseJdkVersion(filename) {
    var n = (filename || '').toLowerCase();
    var m;
    // jdk-8u202 / openjdk-11.0.2 / jdk1.8.0_202 / jdk-17_linux
    if ((m = n.match(/jdk[-_]?(\d+)(?:[._](\d+))?(?:[._](\d+))?/)) && m[1]) {
      var v = 'JDK ' + m[1] + (m[2] ? '.' + m[2] : '') + (m[3] ? '.' + m[3] : '');
      return v;
    }
    if ((m = n.match(/(\d+)\.(\d+)\.(\d+)/))) {
      return 'JDK ' + m[1] + '.' + m[2] + '.' + m[3];
    }
    return '';
  }

  function onJdkFileChange() {
    var f = $('jdkFile').files && $('jdkFile').files[0];
    var hint = $('jdkVerHint');
    if (!f) {
      hint.textContent = '';
      return;
    }
    var v = parseJdkVersion(f.name);
    hint.textContent = v ? ('将安装版本：' + v) : '已选择文件，将按其解压内容安装对应版本';
  }

  function closeJdkModal() {
    $('jdkModalMask').classList.remove('show');
  }

  /** 按指定服务器一键安装 JDK：本地上传压缩包 → 本服务 → SFTP 到目标服务器 → 解压 → 配置环境变量 */
  function installJdk(btn) {
    if (!current) {
      return;
    }
    var file = $('jdkFile').files && $('jdkFile').files[0];
    if (!file) {
      toast('请选择 JDK 安装包', 'err');
      return;
    }
    var name = file.name.toLowerCase();
    if (!(name.endsWith('.tar.gz') || name.endsWith('.tgz') || name.endsWith('.tar') || name.endsWith('.zip'))) {
      toast('只支持 .tar.gz / .tgz / .tar / .zip（解压后须含 bin/java）；.rpm/.deb/.bin 请改用厂商提供的 tar.gz 压缩包', 'err');
      return;
    }
    var installDir = $('jdkInstallDir').value.trim();
    if (!installDir) {
      installDir = '/usr/local/java';
    }
    var linkName = $('jdkLinkName').value.trim();
    if (!$('jdkConfigureEnv').checked) {
      // 后端当前版本固定写入 /etc/profile.d，故取消勾选仍会配置，仅提示
      toast('当前版本固定配置环境变量，已忽略「不配置」', 'err');
    }
    var fd = new FormData();
    fd.append('file', file);
    fd.append('installDir', installDir);
    fd.append('linkName', linkName);
    // 先展示结果面板与上传进度（浏览器→本服务阶段），拿到 taskId 后交由 SSE 接管后续步骤
    $('resultEmpty').style.display = 'none';
    switchTab('result');
    $('overallBarWrap').style.display = 'block';
    $('uploadBox').style.display = 'block';
    updateUploadBar(0, '正在上传 JDK 安装包到本服务…', '');
    busy(btn, true);
    xhrUpload('/api/servers/' + current.id + '/jdk/install', fd, function (pct, loaded, total, speed) {
      updateUploadBar(pct, '正在上传 JDK 安装包到本服务… ' + pct + '%', formatSize(speed) + '/s');
    }).then(function (taskId) {
      closeJdkModal();
      return subscribeTask(taskId);
    }).catch(function (e) {
      $('uploadBox').style.display = 'none';
      toast(e && e.message || '安装失败', 'err');
    }).then(function () {
      busy(btn, false);
    });
  }

  // ------------------------------------------------------------ 日志 / 备份 / 记录

  /** 运行日志当前页签：info / error（默认 info） */
  var logType = 'info';

  /** 按类型取当前发布配置的日志路径（与后端默认规则一致，仅用于界面展示） */
  function currentLogPath(type) {
    if (!currentTarget) { return ''; }
    var t = currentTarget;
    var remote = (t.remoteDir || '').replace(/\/+$/, '');
    var base = stripExtClient(t.jarName || (t.jarNames && t.jarNames[0]) || '') || 'app';
    if (type === 'error') {
      return t.errorLogFile || (remote ? remote + '/logs/' + base + '-error.log' : '');
    }
    return t.infoLogFile || (remote ? remote + '/logs/' + base + '.log' : '');
  }

  function updateLogPathHint() {
    var el = $('logPathHint');
    if (!el) { return; }
    var p = currentLogPath(logType);
    el.textContent = '日志文件：' + (p || '—');
    el.title = p || '';
  }

  /** 切换 info / error 日志页签 */
  function setLogType(type) {
    logType = type === 'error' ? 'error' : 'info';
    Array.prototype.forEach.call(document.querySelectorAll('#logTypeSwitch button'), function (b) {
      b.classList.toggle('active', b.getAttribute('data-logtype') === logType);
    });
    updateLogPathHint();
  }

  function loadLog(btn) {
    if (!current || !currentTarget) {
      toast('请先选择发布配置', 'err');
      return;
    }
    busy(btn, true);
    var lines = parseInt($('logLines').value, 10) || 200;
    var q = $('logKeyword') ? $('logKeyword').value.trim() : '';
    var url = '/api/apps/' + currentTarget.id + '/log?lines=' + lines + '&type=' + logType;
    if (q) { url += '&q=' + encodeURIComponent(q); }
    updateLogPathHint();
    getJson(url).then(function (text) {
      $('logOutput').textContent = text || '（日志为空）';
      var pre = $('logOutput');
      pre.scrollTop = pre.scrollHeight;
    }).catch(function (e) {
      toast(e.message, 'err');
    }).then(function () {
      busy(btn, false);
    });
  }

  function loadBackups(btn) {
    if (!current || !currentTarget) {
      toast('请先选择发布配置', 'err');
      return;
    }
    busy(btn, true);
    getJson('/api/apps/' + currentTarget.id + '/backups').then(function (list) {
      var body = $('backupBody');
      if (!list || list.length === 0) {
        body.innerHTML = '<tr><td colspan="4" style="color:var(--text-sub)">暂无历史备份（首次发布后才会产生）</td></tr>';
        return;
      }
      body.innerHTML = list.map(function (b) {
        return '<tr><td>' + escapeHtml(b.name) + '</td><td>' + escapeHtml(b.size) + '</td><td>'
          + escapeHtml(b.time) + '</td><td><button data-rollback="' + escapeHtml(b.name) + '">回滚</button></td></tr>';
      }).join('');
      Array.prototype.forEach.call(body.querySelectorAll('button[data-rollback]'), function (b) {
        b.onclick = function () {
          var name = b.getAttribute('data-rollback');
          var isFe = currentTarget.type === 'frontend';
          if (!confirm(isFe
              ? '确定用 ' + name + ' 恢复「' + (currentTarget.name || '') + '」的发布目录吗？'
              : '确定把 ' + name + ' 回滚为当前运行包并重启吗？')) {
            return;
          }
          busy(b, true);
          runTask('/api/apps/' + currentTarget.id + '/rollback?name=' + encodeURIComponent(name)
            + '&restart=' + (isFe ? 'false' : 'true'), { method: 'POST' })
            .then(function () { busy(b, false); })
            .catch(function (e) {
              toast(e && e.message || '回滚失败', 'err');
              busy(b, false);
            });
        };
      });
    }).catch(function (e) {
      toast(e.message, 'err');
    }).then(function () {
      busy(btn, false);
    });
  }

  var ACTION_LABELS = {
    CONNECT: '连接', SU: '提权',
    SERVER_ADD: '新增服务器', SERVER_UPDATE: '编辑服务器', SERVER_DELETE: '删除服务器',
    EXPORT: '导出配置', IMPORT: '导入配置',
    SFTP_LIST: '列目录', SFTP_UPLOAD: '上传文件', SFTP_MKDIR: '新建目录', SFTP_DELETE: '删除文件', SFTP_DOWNLOAD: '下载文件',
    EXEC: '命令行',
    DEPLOY: '发布', TEST: '连接测试', ROLLBACK: '回滚', BACKUP: '备份', START: '启动', STOP: '停止', RESTART: '重启'
  };
  function actionLabel(a) {
    return ACTION_LABELS[a] || a;
  }

  function loadOpLogs(btn) {
    busy(btn, true);
    getJson('/api/logs?limit=100').then(function (list) {
      var body = $('opLogBody');
      if (!list || list.length === 0) {
        body.innerHTML = '<tr><td colspan="6" style="color:var(--text-sub)">暂无操作记录</td></tr>';
        return;
      }
      body.innerHTML = list.map(function (o) {
        return '<tr><td>' + escapeHtml(o.time) + '</td><td>' + escapeHtml(o.serverName) + '</td><td>'
          + escapeHtml(actionLabel(o.action)) + '</td><td>' + (o.success ? '<span class="tag run">成功</span>' : '<span class="tag err">失败</span>')
          + '</td><td>' + o.costMs + 'ms</td><td>' + escapeHtml(o.message || '') + '</td></tr>';
      }).join('');
    }).catch(function (e) {
      toast(e.message, 'err');
    }).then(function () {
      busy(btn, false);
    });
  }

  // ------------------------------------------------------------ 配置弹窗

  var FIELDS = ['id', 'name', 'host', 'port', 'authType', 'username', 'password', 'privateKeyPath',
    'passphrase', 'suEnabled', 'suUser', 'suPassword', 'stagingDir', 'remark'];

  function openModal(server) {
    FIELDS.forEach(function (f) {
      var el = $('f-' + f);
      if (!el) {
        return;
      }
      var v = server ? server[f] : '';
      el.value = v === null || v === undefined ? '' : v;
    });
    $('f-suEnabled').checked = !!(server && server.suEnabled);
    if (!server) {
      $('f-port').value = 22;
      $('f-authType').value = 'PASSWORD';
    }
    $('f-password').value = '';
    $('f-passphrase').value = '';
    resetPwdEyes();
    // 所属文件夹下拉：未分组 + 已有文件夹
    var gsel = $('f-group');
    if (gsel) {
      gsel.innerHTML = '';
      var none = document.createElement('option');
      none.value = '';
      none.textContent = '（未分组）';
      gsel.appendChild(none);
      (function () {
        // 已注册文件夹在前，服务器上实际引用但未注册的文件夹随后（去重）
        var seen = {};
        var all = [];
        (groups || []).forEach(function (g) {
          if (!seen[g]) { seen[g] = true; all.push(g); }
        });
        (servers || []).forEach(function (s) {
          var g = s.group && s.group.trim() ? s.group.trim() : '';
          if (g && !seen[g]) { seen[g] = true; all.push(g); }
        });
        all.forEach(function (g) {
          var o = document.createElement('option');
          o.value = g;
          o.textContent = g;
          gsel.appendChild(o);
        });
      })();
      gsel.value = server && server.group ? server.group : '';
    }
    $('modalTitle').textContent = server ? '编辑服务器配置' : '新增服务器';
    $('modalMask').classList.add('show');
  }

  function closeModal() {
    $('modalMask').classList.remove('show');
  }

  /** 把所有密码框重置为密文状态（打开弹窗时调用） */
  function resetPwdEyes() {
    Array.prototype.forEach.call(document.querySelectorAll('.pwd-eye'), function (btn) {
      var input = $(btn.getAttribute('data-toggle'));
      if (input) {
        input.type = 'password';
      }
      btn.textContent = '👁';
      btn.setAttribute('title', '显示密码');
    });
  }

  /** 绑定眼睛图标的点击：切换密码明文/密文 */
  function bindPwdEyes() {
    Array.prototype.forEach.call(document.querySelectorAll('.pwd-eye'), function (btn) {
      btn.onclick = function () {
        var input = $(btn.getAttribute('data-toggle'));
        if (!input) {
          return;
        }
        if (input.type === 'password') {
          input.type = 'text';
          btn.textContent = '🙈';
          btn.setAttribute('title', '隐藏密码');
        } else {
          input.type = 'password';
          btn.textContent = '👁';
          btn.setAttribute('title', '显示密码');
        }
      };
    });
  }

  function saveServer(btn) {
    var body = {};
    FIELDS.forEach(function (f) {
      var el = $('f-' + f);
      if (el) {
        body[f] = el.value.trim();
      }
    });
    body.port = parseInt(body.port, 10) || 22;
    body.suEnabled = $('f-suEnabled').checked;
    var gsel = $('f-group');
    body.group = gsel ? gsel.value : '';
    busy(btn, true);
    postJson('/api/servers', body).then(function (saved) {
      toast('保存成功', 'ok');
      closeModal();
      current = saved;
      return loadServers(true);
    }).then(function () {
      if (current) {
        // 重新加载发布配置（新服务器会自动带一个默认发布配置）
        return loadTargets(current.id, true);
      }
    }).then(function () {
      if (current && currentTarget) {
        refreshStatus();
      }
    }).catch(function (e) {
      toast(e.message, 'err');
    }).then(function () {
      busy(btn, false);
    });
  }

  function deleteServer(server) {
    server = server || current;
    if (!server) {
      return;
    }
    if (!confirm('确定删除配置「' + server.name + '」吗？（只删除本地配置，不影响服务器上的文件）')) {
      return;
    }
    request('/api/servers/' + server.id, { method: 'DELETE' }).then(function () {
      toast('已删除', 'ok');
      if (current && current.id === server.id) {
        current = null;
        targets = [];
        currentTarget = null;
      }
      return loadServers(false);
    }).catch(function (e) {
      toast(e.message, 'err');
    });
  }

  /** 复制服务器及其全部发布配置：克隆连接配置，并把该服务器下所有发布配置一并复制到新服务器 */
  function cloneServer(s) {
    if (!s) {
      return;
    }
    if (!confirm('复制服务器「' + (s.name || '') + '」及其全部发布配置？')) {
      return;
    }
    request('/api/servers/' + s.id + '/clone', { method: 'POST' })
      .then(function (saved) {
        toast('已复制为「' + (saved.name || '') + '」', 'ok');
        return loadServers(true);
      })
      .catch(function (e) {
        toast(e.message, 'err');
      });
  }

  // ------------------------------------------------------------ 配置导入 / 导出（JSON）

  /** 导出全部服务器配置为 JSON 文件下载（密码以 ENC: 密文形式随出，可直接再次导入还原） */
  function exportConfig() {
    getJson('/api/servers/export').then(function (list) {
      if (!list || list.length === 0) {
        toast('当前没有可导出的服务器配置', 'err');
        return;
      }
      var blob = new Blob([JSON.stringify(list, null, 2)], { type: 'application/json' });
      var url = URL.createObjectURL(blob);
      var a = document.createElement('a');
      a.href = url;
      a.download = 'deploy-servers-' + new Date().toISOString().slice(0, 10) + '.json';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
      toast('已导出 ' + list.length + ' 台服务器配置（含发布配置）', 'ok');
    }).catch(function (e) {
      toast(e.message, 'err');
    });
  }

  /** 导入 JSON 文件中的服务器配置（按 host+port+jar+remoteDir 合并） */
  function importConfig(file) {
    if (!file) {
      return;
    }
    var reader = new FileReader();
    reader.onload = function () {
      var arr;
      try {
        arr = JSON.parse(reader.result);
        if (!Array.isArray(arr)) {
          throw new Error('文件格式不正确：应为服务器配置数组');
        }
      } catch (e) {
        toast('解析失败: ' + e.message, 'err');
        return;
      }
      postJson('/api/servers/import', arr).then(function (res) {
        var msg = '导入完成：新增 ' + res.added + ' 台，更新 ' + res.updated + ' 台，发布配置 ' + (res.targets || 0) + ' 个';
        if (res.errors && res.errors.length) {
          msg += '；' + res.errors.length + ' 条失败（详见控制台）';
          console.warn('导入失败项：', res.errors);
        }
        toast(msg, (res.errors && res.errors.length) ? 'err' : 'ok');
        return loadServers(false);
      }).catch(function (e) {
        toast(e.message, 'err');
      });
    };
    reader.readAsText(file);
  }

  // ------------------------------------------------------------ SFTP 文件浏览

  /** 展开 / 收起 SFTP 文件浏览：默认收起，点击「SFTP 传输」才加载并展示 */
  function toggleSftp() {
    if (!current) {
      return;
    }
    var panel = $('sftpPanel');
    if (panel.style.display === 'none' || !panel.style.display) {
      panel.style.display = 'flex';
      loadSftp('');
    } else {
      panel.style.display = 'none';
    }
  }

  /** 加载远程目录（path 为空则家目录） */
  function loadSftp(path) {
    if (!current) {
      return;
    }
    if (typeof path === 'string') {
      sftpCurrentPath = path;
    }
    var listEl = $('sftpList');
    listEl.innerHTML = '<li class="loading"><span class="loading"></span> 加载中…</li>';
    showSftpMsg('', '');
    getJson('/api/servers/' + current.id + '/sftp/list?path=' + encodeURIComponent(sftpCurrentPath || ''))
      .then(renderSftpDir)
      .catch(function (e) {
        listEl.innerHTML = '';
        showSftpMsg(e.message, 'err');
      });
  }

  function renderSftpDir(dir) {
    sftpCurrentPath = dir.path || sftpCurrentPath;
    renderSftpCrumbs(sftpCurrentPath);
    var listEl = $('sftpList');
    if (!dir.entries || dir.entries.length === 0) {
      listEl.innerHTML = '<li class="empty">空目录</li>';
      return;
    }
    listEl.innerHTML = dir.entries.map(function (e) {
      var ic = e.dir ? '📁' : '📄';
      var sz = e.dir ? '—' : formatSize(e.size);
      var full = (sftpCurrentPath.replace(/\/$/, '')) + '/' + e.name;
      var rowCls = e.dir ? 'dir' : '';
      var attr = e.dir ? ' data-path="' + escapeHtml(full) + '"' : '';
      // 文件才显示下载按钮（目录不下载）
      var dlBtn = e.dir ? '' : '<button class="sftp-dl" data-dl="' + escapeHtml(full) + '" title="下载">⬇</button>';
      return '<li class="' + rowCls + '"' + attr + '>'
        + '<span class="ic">' + ic + '</span>'
        + '<span class="nm">' + escapeHtml(e.name) + '</span>'
        + '<span class="sz">' + escapeHtml(sz) + '</span>'
        + '<span class="mt">' + escapeHtml(e.modTime || '') + '</span>'
        + dlBtn
        + '<button class="sftp-del" data-del="' + escapeHtml(full) + '" title="删除">🗑</button>'
        + '</li>';
    }).join('');
    Array.prototype.forEach.call(listEl.querySelectorAll('li.dir'), function (li) {
      li.onclick = function (ev) {
        if (ev.target && (ev.target.classList.contains('sftp-del') || ev.target.classList.contains('sftp-dl'))) {
          return;
        }
        loadSftp(li.getAttribute('data-path'));
      };
    });
    Array.prototype.forEach.call(listEl.querySelectorAll('.sftp-del'), function (btn) {
      btn.onclick = function (ev) {
        ev.stopPropagation();
        sftpDelete(btn.getAttribute('data-del'));
      };
    });
    Array.prototype.forEach.call(listEl.querySelectorAll('.sftp-dl'), function (btn) {
      btn.onclick = function (ev) {
        ev.stopPropagation();
        sftpDownload(btn.getAttribute('data-dl'));
      };
    });
  }

  /** 删除远程文件或目录（带二次确认） */
  function sftpDelete(fullPath) {
    if (!current) {
      return;
    }
    if (!confirm('确定删除 ' + fullPath + ' 吗？（不可恢复）')) {
      return;
    }
    postJson('/api/servers/' + current.id + '/sftp/delete?path=' + encodeURIComponent(fullPath), {})
      .then(function () {
        showSftpMsg('已删除：' + fullPath, 'ok');
        loadSftp(sftpCurrentPath);
      })
      .catch(function (e) { showSftpMsg(e.message, 'err'); });
  }

  /** 下载远程文件到本地（经本服务中转流式回传，root-only 文件在开启 su 时也可下载） */
  function sftpDownload(fullPath) {
    if (!current) {
      return;
    }
    var name = (fullPath.split('/').pop()) || 'download';
    showSftpMsg('正在下载 ' + name + ' …', '');
    fetch('/api/servers/' + current.id + '/sftp/download?path=' + encodeURIComponent(fullPath))
      .then(function (res) {
        if (!res.ok) {
          // 服务端在响应未提交时返回 JSON 错误（HTTP 500）
          return res.json().then(function (j) {
            throw new Error(j.message || '下载失败');
          }, function () {
            throw new Error('下载失败（HTTP ' + res.status + '）');
          });
        }
        // 从 Content-Disposition 解析文件名（filename*=UTF-8''...）
        var cd = res.headers.get('Content-Disposition') || '';
        var m = cd.match(/filename\*=UTF-8''([^;]+)/);
        if (m) {
          try { name = decodeURIComponent(m[1]); } catch (e) { /* ignore */ }
        }
        return res.blob().then(function (b) {
          var url = URL.createObjectURL(b);
          var a = document.createElement('a');
          a.href = url;
          a.download = name;
          document.body.appendChild(a);
          a.click();
          document.body.removeChild(a);
          URL.revokeObjectURL(url);
          showSftpMsg('已下载：' + name + '（' + formatSize(b.size) + '）', 'ok');
        });
      })
      .catch(function (e) { showSftpMsg(e && e.message || '下载失败', 'err'); });
  }

  /** 渲染路径面包屑，每段可点击跳转 */
  function renderSftpCrumbs(path) {
    var box = $('sftpCrumbs');
    if (!path || path === '/') {
      box.innerHTML = '<span class="crumb" data-path="/">根目录 /</span>';
      box.querySelector('.crumb').onclick = function () { loadSftp('/'); };
      return;
    }
    var parts = path.split('/').filter(function (p) { return p.length > 0; });
    var html = '<span class="crumb" data-path="/">根</span>';
    var acc = '';
    parts.forEach(function (p) {
      acc += '/' + p;
      html += '<span class="sep">/</span><span class="crumb" data-path="' + escapeHtml(acc) + '">' + escapeHtml(p) + '</span>';
    });
    box.innerHTML = html;
    Array.prototype.forEach.call(box.querySelectorAll('.crumb'), function (c) {
      c.onclick = function () { loadSftp(c.getAttribute('data-path')); };
    });
  }

  /** 上传文件到当前目录，带浏览器→服务器进度 */
  function sftpUpload(file) {
    if (!current) {
      return;
    }
    if (!file) {
      return;
    }
    if (!sftpCurrentPath) {
      sftpCurrentPath = '/';
    }
    var fd = new FormData();
    fd.append('file', file);
    fd.append('path', sftpCurrentPath);
    var box = $('sftpProgress');
    box.style.display = 'block';
    $('sftpProgressBar').style.width = '0%';
    $('sftpProgressPct').textContent = '0%';
    $('sftpProgressText').textContent = '正在上传到服务器…';
    showSftpMsg('', '');
    xhrUpload('/api/servers/' + current.id + '/sftp/upload', fd, function (pct) {
      $('sftpProgressBar').style.width = pct + '%';
      $('sftpProgressPct').textContent = pct + '%';
    }).then(function (res) {
      $('sftpProgressText').textContent = '已写入远程：' + (res.remotePath || '') + '（' + formatSize(res.size || 0) + '）';
      showSftpMsg('上传完成：' + (res.name || ''), 'ok');
      loadSftp(sftpCurrentPath);
    }).catch(function (e) {
      $('sftpProgressText').textContent = '上传失败';
      showSftpMsg(e.message || '上传失败', 'err');
    });
  }

  /** 在当前目录下新建文件夹 */
  function sftpMkdir() {
    if (!current) {
      return;
    }
    var name = prompt('请输入要创建的文件夹名称：');
    if (!name) {
      return;
    }
    name = name.trim();
    if (!name) {
      return;
    }
    var full = (sftpCurrentPath || '/').replace(/\/$/, '') + '/' + name;
    postJson('/api/servers/' + current.id + '/sftp/mkdir?path=' + encodeURIComponent(full), {})
      .then(function () {
        showSftpMsg('已创建目录：' + full, 'ok');
        loadSftp(sftpCurrentPath);
      })
      .catch(function (e) { showSftpMsg(e.message, 'err'); });
  }

  function showSftpMsg(msg, type) {
    var el = $('sftpMsg');
    el.textContent = msg || '';
    el.className = 'sftp-msg' + (type ? ' ' + type : '');
  }

  // ------------------------------------------------------------ 命令行执行（终端式）

  /** 当前服务器的终端工作目录（服务端按 server 维护，cd 后更新） */
  var cmdCwd = '';
  /** 当前终端实际执行身份（su 提权后为 root 等目标用户，否则为登录账号） */
  var cmdUser = '';
  /** 命令历史上下键导航状态 */
  var cmdHistIdx = -1;
  var cmdHistDraft = '';
  /** vi 在线编辑状态：原始命令 / 已解析绝对路径 / 所属终端块 / 是否有未保存修改 */
  var viCmd = '';
  var viPath = '';
  var viBlock = null;
  var viDirty = false;

  /** 执行身份初始值：服务器配置开启 su 时即为目标用户（默认 root） */
  function initialCmdUser() {
    if (!current) {
      return '';
    }
    if (current.suEnabled) {
      return current.suUser || 'root';
    }
    return current.username || '';
  }

  function cmdHost() {
    return current ? current.host : '';
  }

  function cmdPromptUser() {
    return cmdUser || (current ? current.username : '');
  }

  function cmdCwdText() {
    return cmdCwd || '~';
  }

  /** root 用 #、普通用户用 $，与真实 shell 一致 */
  function cmdPromptSuffix() {
    return cmdPromptUser() === 'root' ? '#' : '$';
  }

  function updateCmdCwd() {
    $('cmdCwd').textContent = cmdCwdText();
    $('cmdCwd').title = cmdCwd || '登录默认目录（未切换）';
    updateCmdPrompt();
  }

  /** 更新终端底部输入行的提示符（随当前目录实时变化） */
  function updateCmdPrompt() {
    $('cmdPrompt').textContent = '[' + cmdPromptUser() + '@' + cmdHost() + ' ' + cmdCwdText() + ']' + cmdPromptSuffix() + ' ';
  }

  /** 渲染执行身份徽章：root 高亮，未提权的普通用户给出配置提示 */
  function updateCmdUser() {
    var el = $('cmdUser');
    var user = cmdPromptUser();
    if (!user) {
      el.textContent = '—';
      el.className = 'cmd-user';
      el.title = '';
      return;
    }
    el.textContent = user;
    if (user === 'root') {
      el.className = 'cmd-user root';
      el.title = '当前以 root 身份执行命令';
    } else if (current && current.suEnabled) {
      el.className = 'cmd-user';
      el.title = '以 ' + user + ' 身份执行';
    } else {
      el.className = 'cmd-user plain';
      el.title = '以登录账号 ' + user + ' 身份执行（未提权）。如需自动切换 root，请在「编辑配置 → 提权」中开启「登录后 su 切换」并填写密码。';
    }
    updateCmdPrompt();
  }

  /** 清空终端内容并重置本地目录显示 */
  function resetCmdTerminal() {
    cmdCwd = '';
    cmdUser = initialCmdUser();
    $('cmdBlocks').innerHTML = '';
    hideCmdComplete();
    cmdHistIdx = -1;
    cmdHistDraft = '';
    updateCmdCwd();
    updateCmdPrompt();
    updateCmdUser();
  }

  /** 在终端区追加一个命令块（提示符 + 命令 + 输出 + meta），返回待填充的元素 */
  function appendCmdBlock(cmd) {
    var term = $('cmdOutput');
    var block = document.createElement('div');
    block.className = 'cmd-block';
    var line = document.createElement('div');
    line.className = 'cmd-line';
    line.innerHTML = '<span class="cmd-prompt">[' + escapeHtml(cmdPromptUser()) + '@' + escapeHtml(cmdHost()) + ' '
      + escapeHtml(cmdCwdText()) + ']' + cmdPromptSuffix() + ' </span><span class="cmd-cmd-text">' + escapeHtml(cmd) + '</span>';
    var out = document.createElement('div');
    out.className = 'cmd-out running';
    out.textContent = '执行中';
    var meta = document.createElement('div');
    meta.className = 'cmd-meta';
    block.appendChild(line);
    block.appendChild(out);
    block.appendChild(meta);
    $('cmdBlocks').appendChild(block);
    term.scrollTop = term.scrollHeight;
    return { out: out, meta: meta };
  }

  /** 在终端输入行回车执行：命令即刻写入终端，完成后就地更新结果 */
  function execCommand() {
    if (!current) {
      toast('请先选择服务器', 'err');
      return;
    }
    var input = $('cmdInput');
    var cmd = input.value.trim();
    if (!cmd) {
      return;
    }
    input.value = '';
    cmdHistIdx = -1;
    cmdHistDraft = '';
    var el = appendCmdBlock(cmd);
    if (isViCommand(cmd)) {
      // vi/vim：非交互终端无法运行，改为打开网页编辑器编辑远程文件，支持 :wq! 保存
      openViEditor(cmd, el);
    } else if (isStreamingCommand(cmd)) {
      // tail -f、top 等持续输出命令：SSE 流式实时打印，可点「停止」退出
      execStreamCommand(cmd, el);
    } else {
      postJson('/api/servers/' + current.id + '/exec?command=' + encodeURIComponent(cmd), {})
        .then(function (r) {
          if (r.user) {
            cmdUser = r.user;
            updateCmdUser();
          }
          if (r.cwd) {
            cmdCwd = r.cwd;
            updateCmdCwd();
          }
          var exit = r.exitCode;
          var txt = r.stdout || '';
          if (r.stderr) {
            txt += (txt ? '\n' : '') + '—— stderr ——\n' + r.stderr;
          }
          el.out.className = 'cmd-out ' + (exit === 0 ? 'ok' : 'err');
          el.out.textContent = txt || '（命令执行完成，无输出）';
          el.meta.className = 'cmd-meta ' + (exit === 0 ? 'ok' : 'err');
          el.meta.textContent = '退出码 ' + exit + ' · 耗时 ' + r.costMs + 'ms'
            + (r.user ? ' · 身份 ' + r.user : '')
            + (r.cwd ? ' · 目录 ' + r.cwd : '');
          $('cmdOutput').scrollTop = $('cmdOutput').scrollHeight;
          addCmdHistory(cmd);
        })
        .catch(function (e) {
          el.out.className = 'cmd-out err';
          el.out.textContent = '执行失败: ' + (e && e.message || '未知错误');
          el.meta.className = 'cmd-meta err';
          el.meta.textContent = '执行失败';
          $('cmdOutput').scrollTop = $('cmdOutput').scrollHeight;
          toast(e && e.message || '执行失败', 'err');
        });
    }
    input.focus();
  }

  /** 判断是否为持续输出命令（tail -f、top、less 等），需流式执行 */
  function isStreamingCommand(cmd) {
    var m = cmd.match(/^\s*(\S+)/);
    if (!m) {
      return false;
    }
    var base = m[1];
    if (base === 'top' || base === 'htop' || base === 'less' || base === 'more'
        || base === 'watch' || base === 'ping') {
      return true;
    }
    if (base === 'tail' && /\s-{1,2}[a-zA-Z]*[fF]\b/.test(cmd)) {
      return true;
    }
    return false;
  }

  /**
   * 流式执行持续输出命令：SSE 实时追加输出到终端，meta 区提供「停止」按钮退出。
   * 退出码为负表示被手动停止。
   */
  function execStreamCommand(cmd, el) {
    var sid = 's' + Date.now().toString(36) + Math.random().toString(36).slice(2);
    var done = false;
    el.out.className = 'cmd-out running';
    el.out.textContent = '跟踪中…（输出实时打印，点「停止」退出）';
    var btnStop = document.createElement('button');
    btnStop.type = 'button';
    btnStop.className = 'cmd-stop-btn';
    btnStop.textContent = '停止';
    el.meta.appendChild(btnStop);
    btnStop.onclick = function () {
      if (done) {
        return;
      }
      btnStop.disabled = true;
      btnStop.textContent = '正在停止…';
      post('/api/servers/' + current.id + '/exec/stream/' + sid + '/stop')
        .then(function () {
          if (!done) {
            btnStop.textContent = '已停止';
          }
        })
        .catch(function () {
          btnStop.disabled = false;
          btnStop.textContent = '停止';
        });
    };
    var es = new EventSource('/api/servers/' + current.id + '/exec/stream?sessionId=' + sid
      + '&command=' + encodeURIComponent(cmd));
    es.addEventListener('out', function (e) {
      el.out.textContent += e.data;
      $('cmdOutput').scrollTop = $('cmdOutput').scrollHeight;
    });
    es.addEventListener('exit', function (e) {
      done = true;
      var code = parseInt(e.data, 10);
      var stopped = code < 0;
      el.out.className = 'cmd-out ' + (stopped || code === 0 ? 'ok' : 'err');
      el.meta.className = 'cmd-meta ' + (stopped || code === 0 ? 'ok' : 'err');
      el.meta.textContent = stopped ? '已停止' : '退出码 ' + code + ' · 实时跟踪结束';
      es.close();
    });
    es.onerror = function () {
      es.close();
    };
  }

  // ------------------------------------------------------------ vi 远程文件编辑

  /** 判断是否为 vi/vim 打开文件的命令（如 vi /opt/app/application.yml） */
  function isViCommand(cmd) {
    return /^\s*(vi|vim)(\s|$)/.test(cmd);
  }

  /** 从 vi 命令中解析文件路径：取最后一个非选项 token（-- 之后的都算文件） */
  function parseViPath(cmd) {
    var parts = cmd.trim().split(/\s+/).slice(1);
    var path = '';
    for (var i = 0; i < parts.length; i++) {
      if (parts[i] === '--') {
        if (parts[i + 1]) {
          path = parts[i + 1];
        }
        break;
      }
      if (parts[i].charAt(0) !== '-') {
        path = parts[i];
      }
    }
    return path;
  }

  /** 打开 vi 编辑器：加载远程文件内容到弹窗，保存即 :wq! 写回 */
  function openViEditor(cmd, el) {
    var p = parseViPath(cmd);
    if (!p) {
      el.out.className = 'cmd-out err';
      el.out.textContent = '请在 vi 命令后指定要编辑的文件，例如：vi /opt/app/application.yml';
      el.meta.className = 'cmd-meta err';
      el.meta.textContent = 'vi 未指定文件';
      toast('vi 需指定文件路径', 'err');
      return;
    }
    viCmd = cmd;
    viBlock = el;
    viPath = '';
    viDirty = false;
    el.out.className = 'cmd-out running';
    el.out.textContent = '正在从远程加载 ' + p + ' …';
    postJson('/api/servers/' + current.id + '/exec/vi/load', { path: p, cwd: cmdCwd || '' })
      .then(function (r) {
        viPath = r.path;
        if (r.dir) {
          el.out.className = 'cmd-out err';
          el.out.textContent = '无法编辑：' + r.path + ' 是一个目录';
          el.meta.className = 'cmd-meta err';
          el.meta.textContent = 'vi 无法打开目录';
          toast('不能打开目录', 'err');
          return;
        }
        showViEditor(r, el);
      })
      .catch(function (e) {
        el.out.className = 'cmd-out err';
        el.out.textContent = '打开 vi 失败: ' + (e && e.message || '未知错误');
        el.meta.className = 'cmd-meta err';
        el.meta.textContent = 'vi 打开失败';
        toast(e && e.message || '打开 vi 失败', 'err');
      });
  }

  /** 填充编辑弹窗并展示 */
  function showViEditor(r, el) {
    busy($('btnViWq'), false);
    busy($('btnViW'), false);
    $('viPath').textContent = r.path + (r.exists ? '' : '（新文件，保存时创建）');
    $('viMeta').textContent = (r.exists ? r.size + 'B · ' : '') + 'Ctrl+S 或输入 :wq! 保存并退出';
    $('viEditor').value = r.content || '';
    $('viCmdInput').value = '';
    $('viTitle').textContent = 'vi — ' + r.path;
    $('viModalMask').className = 'modal-mask show';
    el.out.className = 'cmd-out ok';
    el.out.textContent = 'vi 编辑器已打开：' + r.path + (r.exists ? '' : '（新文件）');
    el.meta.className = 'cmd-meta ok';
    el.meta.textContent = '编辑完成后点「保存并退出 (:wq!)」写回远程';
    $('cmdOutput').scrollTop = $('cmdOutput').scrollHeight;
    $('viEditor').focus();
  }

  /** 执行 vi 命令（底部输入框，如 :wq!）；mode: 'save' | 'saveQuit' | 'quit' */
  function viExec(mode) {
    if (mode === 'quit') {
      if (viDirty && !confirm('文件有未保存的修改，确定放弃退出（:q!）吗？')) {
        $('viCmdInput').value = '';
        $('viEditor').focus();
        return;
      }
      closeViEditor(false);
      return;
    }
    var content = $('viEditor').value;
    if (content.length > 2 * 1024 * 1024) {
      toast('内容过大，超过 2MB 上限', 'err');
      return;
    }
    var btn = mode === 'saveQuit' ? $('btnViWq') : $('btnViW');
    busy(btn, true);
    postJson('/api/servers/' + current.id + '/exec/vi/save', { path: viPath, content: content })
      .then(function (r) {
        $('viMeta').textContent = '已保存 ' + r.size + 'B · ' + new Date().toLocaleTimeString();
        viDirty = false;
        if (mode === 'saveQuit') {
          toast('已保存 ' + r.path, 'ok');
          closeViEditor(true);
        } else {
          busy($('btnViW'), false);
          $('viCmdInput').value = '';
          $('viEditor').focus();
          toast('已保存（:w）', 'ok');
        }
      })
      .catch(function (e) {
        toast(e && e.message || '保存失败', 'err');
        busy($('btnViWq'), false);
        busy($('btnViW'), false);
      });
  }

  /** 关闭 vi 编辑器，并把结果写回终端命令块 */
  function closeViEditor(saved) {
    $('viModalMask').className = 'modal-mask';
    if (viBlock) {
      viBlock.out.className = 'cmd-out ' + (saved ? 'ok' : 'err');
      viBlock.out.textContent = saved
        ? '已保存文件 ' + viPath + '（:wq! 保存并退出）'
        : '已退出 vi：' + viPath + '（未保存修改）';
      viBlock.meta.className = 'cmd-meta ' + (saved ? 'ok' : 'err');
      viBlock.meta.textContent = saved ? '保存成功' : '放弃修改';
      $('cmdOutput').scrollTop = $('cmdOutput').scrollHeight;
    }
    if (viCmd) {
      addCmdHistory(viCmd);
    }
    viCmd = '';
    viPath = '';
    viBlock = null;
    viDirty = false;
    $('cmdInput').focus();
  }

  /** ↑ 键：回填上一条历史命令 */
  function cmdHistUp() {
    var arr = getCmdHistory();
    if (!arr.length) {
      return;
    }
    var input = $('cmdInput');
    if (cmdHistIdx < 0) {
      cmdHistDraft = input.value;
      cmdHistIdx = arr.length - 1;
    } else if (cmdHistIdx > 0) {
      cmdHistIdx -= 1;
    }
    input.value = arr[cmdHistIdx] || '';
  }

  /** ↓ 键：前进到下一条历史命令（越界回到输入草稿） */
  function cmdHistDown() {
    var arr = getCmdHistory();
    if (cmdHistIdx < 0) {
      return;
    }
    var input = $('cmdInput');
    if (cmdHistIdx < arr.length - 1) {
      cmdHistIdx += 1;
      input.value = arr[cmdHistIdx] || '';
    } else {
      cmdHistIdx = -1;
      input.value = cmdHistDraft;
    }
  }

  function clearCommand() {
    $('cmdInput').value = '';
    resetCmdTerminal();
    $('cmdInput').focus();
  }

  // ------------------------------------------------------------ Tab 自动补全

  /** 隐藏补全候选列表 */
  function hideCmdComplete() {
    var el = $('cmdComplete');
    el.style.display = 'none';
    el.innerHTML = '';
  }

  /** 展示补全候选列表（目录蓝色高亮，点击回填） */
  function showCmdComplete(items) {
    var el = $('cmdComplete');
    el.innerHTML = items.map(function (it) {
      var isDir = it.charAt(it.length - 1) === '/';
      return '<span class="cmd-comp-item' + (isDir ? ' dir' : '') + '">' + escapeHtml(it) + '</span>';
    }).join('');
    el.style.display = 'flex';
    Array.prototype.forEach.call(el.querySelectorAll('.cmd-comp-item'), function (n, i) {
      n.onclick = function () {
        var input = $('cmdInput');
        var sp = input.value.lastIndexOf(' ');
        var head = sp >= 0 ? input.value.substring(0, sp + 1) : '';
        input.value = head + shellQuoteToken(items[i]);
        hideCmdComplete();
        input.focus();
      };
    });
  }

  /** 计算一批候选的最长公共前缀 */
  function longestCommonPrefix(arr) {
    if (!arr.length) {
      return '';
    }
    var p = arr[0];
    for (var i = 1; i < arr.length; i++) {
      while (arr[i].indexOf(p) !== 0) {
        p = p.substring(0, p.length - 1);
        if (!p) {
          return '';
        }
      }
    }
    return p;
  }

  /** 补全结果拼回输入框：含空白/特殊字符时用单引号包裹，避免被 shell 截断 */
  function shellQuoteToken(s) {
    if (/[\s"'$`\\*?;|&<>(){}!#]/.test(s)) {
      return "'" + s.replace(/'/g, "'\\''") + "'";
    }
    return s;
  }

  /** Tab 键：请求远端补全；唯一候选直接替换，多候选补公共前缀并列出可点击 */
  function cmdComplete() {
    if (!current) {
      return;
    }
    var input = $('cmdInput');
    var txt = input.value;
    postJson('/api/servers/' + current.id + '/exec/complete?input=' + encodeURIComponent(txt), {})
      .then(function (r) {
        var items = r.candidates || [];
        if (!items.length) {
          hideCmdComplete();
          return;
        }
        var sp = txt.lastIndexOf(' ');
        var head = sp >= 0 ? txt.substring(0, sp + 1) : '';
        var prefix = sp >= 0 ? txt.substring(sp + 1) : txt;
        if (items.length === 1) {
          input.value = head + shellQuoteToken(items[0]);
          hideCmdComplete();
        } else {
          var common = longestCommonPrefix(items);
          if (common.length > prefix.length) {
            input.value = head + shellQuoteToken(common);
          }
          showCmdComplete(items);
        }
        input.focus();
      })
      .catch(function () {
        hideCmdComplete();
      });
  }

  /** 命令历史（localStorage，最近 12 条），点击回填到输入框 */
  function getCmdHistory() {
    try {
      var arr = JSON.parse(localStorage.getItem('cmdHistory') || '[]');
      return Array.isArray(arr) ? arr : [];
    } catch (e) {
      return [];
    }
  }

  function addCmdHistory(cmd) {
    var arr = getCmdHistory().filter(function (c) { return c !== cmd; });
    arr.unshift(cmd);
    localStorage.setItem('cmdHistory', JSON.stringify(arr.slice(0, 12)));
    renderCmdHistory();
  }

  function renderCmdHistory() {
    var arr = getCmdHistory();
    var box = $('cmdHistory');
    if (!arr.length) {
      box.innerHTML = '';
      return;
    }
    box.innerHTML = '<span class="cmd-hist-label">最近：</span>' + arr.map(function (c) {
      return '<span class="cmd-hist-item" title="点击回填">' + escapeHtml(c) + '</span>';
    }).join('');
    Array.prototype.forEach.call(box.querySelectorAll('.cmd-hist-item'), function (el) {
      el.onclick = function () {
        $('cmdInput').value = el.textContent;
        $('cmdInput').focus();
      };
    });
  }

  // ------------------------------------------------------------ Tab

  function switchTab(tab) {
    ['result', 'log', 'backup', 'oplog', 'cmd'].forEach(function (t) {
      $('tab-' + t).style.display = t === tab ? 'block' : 'none';
    });
    Array.prototype.forEach.call(document.querySelectorAll('.tabbar button'), function (b) {
      b.classList.toggle('active', b.getAttribute('data-tab') === tab);
    });
    if (tab === 'backup') {
      loadBackups();
    }
    if (tab === 'oplog') {
      loadOpLogs();
    }
    if (tab === 'cmd') {
      renderCmdHistory();
      updateCmdPrompt();
      $('cmdInput').focus();
    }
    if (tab === 'log') {
      // 进入运行日志页签时按当前 info/error 选择自动拉取一次，省掉再点一次查询
      updateLogPathHint();
      if (currentTarget) {
        loadLog();
      }
    }
  }

  // ------------------------------------------------------------ 本机路径选择弹窗

  var pathCtx = { mode: 'any', selected: '', selectedIsDir: false, onConfirm: null, sep: '\\' };
  var PATH_LAST_DIR_KEY = 'pathBrowserLastDir';

  /**
   * 打开本机路径选择弹窗。
   * opts: { title, mode: 'any'|'dir', initial: 起始路径, onConfirm(path) }
   * 起始路径优先级：已配置的路径 → 上次浏览过的目录（localStorage）→ 用户主目录。
   * mode 'any'：文件 / 文件夹均可选中，未选中时确认即取当前目录；
   * mode 'dir'：面向目录选择，行为同 'any'（选了文件夹则取文件夹，否则取当前目录）。
   */
  function openPathBrowser(opts) {
    pathCtx.mode = (opts && opts.mode) || 'any';
    pathCtx.selected = '';
    pathCtx.selectedIsDir = false;
    pathCtx.onConfirm = opts && opts.onConfirm;
    $('pathModalTitle').textContent = pathCtx.mode === 'dir' ? '📁 选择目录' : '📁 选择路径';
    $('pathSelectedLabel').textContent = '未选择（确认将填入当前目录）';
    var initial = (opts && opts.initial && opts.initial.trim())
        || localStorage.getItem(PATH_LAST_DIR_KEY) || '';
    loadPathDir(initial);
    $('pathModalMask').classList.add('show');
  }

  function closePathModal() {
    $('pathModalMask').classList.remove('show');
    pathCtx.onConfirm = null;
  }

  /** 加载目录列表；path 为空时由后端定位到用户主目录 */
  function loadPathDir(path) {
    $('pathList').innerHTML = '<div class="path-empty">加载中…</div>';
    getJson('/api/local-files?path=' + encodeURIComponent(path || ''))
      .then(function (d) { renderPathList(d); })
      .catch(function (e) {
        $('pathList').innerHTML = '<div class="path-empty">加载失败：' + escapeHtml(e.message) + '</div>';
      });
  }

  function renderPathList(d) {
    pathCtx.sep = (d.path || '').indexOf('/') >= 0 && (d.path || '').indexOf('\\') < 0 ? '/' : '\\';
    pathCtx.selected = '';
    pathCtx.selectedIsDir = false;
    $('pathInput').value = d.path || '';
    $('pathSelectedLabel').textContent = '未选择（确认将填入当前目录）';

    // 盘符下拉
    var drive = $('pathDriveSel');
    drive.innerHTML = (d.roots || []).map(function (r) {
      return '<option value="' + escapeHtml(r) + '">' + escapeHtml(r) + '</option>';
    }).join('');
    var curRoot = (d.roots || []).filter(function (r) {
      return (d.path || '').toLowerCase().indexOf(r.toLowerCase()) === 0;
    })[0];
    if (curRoot) {
      drive.value = curRoot;
    }

    // 快速访问
    var quick = $('pathQuick');
    quick.innerHTML = '';
    (d.quick || []).forEach(function (q) {
      var b = document.createElement('button');
      b.type = 'button';
      b.textContent = q.label;
      b.onclick = function () { loadPathDir(q.path); };
      quick.appendChild(b);
    });

    var list = $('pathList');
    list.innerHTML = '';
    if (d.parent) {
      var up = document.createElement('div');
      up.className = 'path-row up';
      up.innerHTML = '<span class="path-ico">⬆</span><span class="path-name">.. （上一级）</span>';
      up.onclick = function () { loadPathDir(d.parent); };
      list.appendChild(up);
    }
    (d.entries || []).forEach(function (e) {
      var row = document.createElement('div');
      row.className = 'path-row';
      row.innerHTML = '<span class="path-ico">' + (e.dir ? '📁' : '📄') + '</span>'
        + '<span class="path-name">' + escapeHtml(e.name) + '</span>'
        + '<span class="path-size">' + (e.dir ? '' : formatSize(e.size || 0)) + '</span>';
      row.onclick = function () {
        Array.prototype.forEach.call(list.querySelectorAll('.path-row'), function (r) {
          r.classList.remove('selected');
        });
        row.classList.add('selected');
        pathCtx.selected = joinPath(d.path, e.name);
        pathCtx.selectedIsDir = !!e.dir;
        $('pathSelectedLabel').textContent = pathCtx.selected;
      };
      if (e.dir) {
        row.ondblclick = function () { loadPathDir(joinPath(d.path, e.name)); };
      }
      list.appendChild(row);
    });
    if (!(d.entries || []).length && !d.parent) {
      list.innerHTML = '<div class="path-empty">空目录</div>';
    }
  }

  function joinPath(dir, name) {
    if (!dir) { return name; }
    var sep = pathCtx.sep;
    return dir.charAt(dir.length - 1) === sep || dir.charAt(dir.length - 1) === '/'
        ? dir + name : dir + sep + name;
  }

  function confirmPathModal() {
    var cur = $('pathInput').value.trim();
    // 已选中某行（文件或文件夹均可）则取选中项，否则取当前目录
    var val = pathCtx.selected || cur;
    if (!val) {
      toast('请先选择路径', 'err');
      return;
    }
    // 记住本次浏览的目录，下次打开直接从这里开始
    if (cur) {
      localStorage.setItem(PATH_LAST_DIR_KEY, cur);
    }
    var cb = pathCtx.onConfirm;
    closePathModal();
    if (cb) { cb(val); }
  }

  // ------------------------------------------------------------ 事件绑定

  document.addEventListener('DOMContentLoaded', function () {
    bindPwdEyes();
    loadServers(false);

    $('btnExport').onclick = exportConfig;
    $('btnImport').onclick = function () {
      $('importFile').click();
    };
    $('importFile').onchange = function () {
      if (this.files && this.files[0]) {
        importConfig(this.files[0]);
        this.value = '';
      }
    };
    $('btnRefreshAll').onclick = function () {
      loadServers(true);
    };
    $('btnAdd').onclick = function () {
      openModal(null);
    };
    $('btnAddGroup').onclick = createGroup;
    $('btnCancel').onclick = closeModal;
    $('btnCloseModal').onclick = closeModal;
    $('btnSave').onclick = function () {
      saveServer(this);
    };
    $('btnAddTarget').onclick = function () {
      openTargetModal(null);
    };
    $('btnDeploySequential').onclick = deploySequential;
    $('btnRestartBatch').onclick = restartBatch;
    $('targetSelectAll').onchange = function () {
      var checked = this.checked;
      targets.forEach(function (t) {
        if (checked) {
          selectedTargetIds[t.id] = true;
          selectedTargetMap[t.id] = t;
        } else {
          delete selectedTargetIds[t.id];
          delete selectedTargetMap[t.id];
        }
      });
      renderTargets();
    };
    $('btnCloseTargetModal').onclick = closeTargetModal;
    $('btnCancelTarget').onclick = closeTargetModal;
    $('btnSaveTarget').onclick = function () {
      saveTarget(this, false);
    };
    // 保存并上传文件：只更新文件（不备份/不停止/不重启），部署仍由外层「一键发布」触发
    var btnSaveAndUpload = $('btnSaveAndUpload');
    if (btnSaveAndUpload) {
      btnSaveAndUpload.onclick = function () {
        saveTarget(this, true);
      };
    }
    // 发布配置弹窗：Ctrl / Cmd + S 快捷保存，Esc 关闭（退出）
    document.addEventListener('keydown', function (e) {
      var tm = $('targetModalMask');
      if (!tm || !tm.classList.contains('show')) {
        return;
      }
      if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
        e.preventDefault();
        saveTarget($('btnSaveTarget'));
      } else if (e.key === 'Escape' || e.key === 'Esc') {
        e.preventDefault();
        closeTargetModal();
      }
    });
    $('btnCloseCopyTo').onclick = closeCopyTo;
    $('btnCancelCopyTo').onclick = closeCopyTo;
    $('btnConfirmCopyTo').onclick = confirmCopyTo;
    $('btnCloseJdkModal').onclick = closeJdkModal;
    $('btnCancelJdk').onclick = closeJdkModal;
    $('btnInstallJdk').onclick = function () { installJdk(this); };
    $('jdkFile').onchange = onJdkFileChange;
    $('btnNacos').onclick = openNacosModal;
    $('btnCloseNacos').onclick = closeNacosModal;
    $('btnCancelNacos').onclick = closeNacosModal;
    $('btnDeployNacos').onclick = nacosDeploy;
    // 免密模式：已移除登录验证开关 / 退出登录入口
    $('btnDisk').onclick = openDiskModal;
    $('btnCloseDisk').onclick = closeDiskModal;
    $('btnCancelDisk').onclick = closeDiskModal;
    $('btnDiskScan').onclick = diskScan;
    $('diskServerSel').onchange = function () { resetDiskScan(); };
    $('diskSelAll').onchange = function () {
      var cbs = $('diskBody').querySelectorAll('.disk-cb');
      for (var i = 0; i < cbs.length; i++) {
        var row = cbs[i].closest('tr');
        if (row && row.getAttribute('data-mountable') === '1') {
          cbs[i].checked = this.checked;
        }
      }
      syncDiskSelection();
    };
    $('btnMountDisk').onclick = diskMount;
    // 批量修改发布目录（前缀替换）
    $('btnBatchDir').onclick = openBatchDir;
    $('btnCloseBatchDir').onclick = closeBatchDirModal;
    $('btnCancelBatchDir').onclick = closeBatchDirModal;
    $('btnConfirmBatchDir').onclick = confirmBatchDir;
    // 批量修改 JVM 参数
    $('btnBatchJvm').onclick = openBatchJvm;
    $('btnCloseBatchJvm').onclick = closeBatchJvmModal;
    $('btnCancelBatchJvm').onclick = closeBatchJvmModal;
    $('btnConfirmBatchJvm').onclick = confirmBatchJvm;
    // 本机路径选择弹窗：浏览选路径，不用手动填写
    $('btnBrowseLocalPath').onclick = function () {
      openPathBrowser({
        mode: 'any',
        title: '选择路径',
        initial: $('t-localPath').value.trim(),
        onConfirm: function (p) { $('t-localPath').value = p; }
      });
    };
    $('btnBrowseFrontendPath').onclick = function () {
      openPathBrowser({
        mode: 'dir',
        title: '选择本机发布目录',
        initial: $('t-frontendPath').value.trim(),
        onConfirm: function (p) { $('t-frontendPath').value = p; }
      });
    };
    $('btnClosePathModal').onclick = closePathModal;
    $('btnCancelPathModal').onclick = closePathModal;
    $('btnConfirmPathModal').onclick = confirmPathModal;
    $('btnPathRefresh').onclick = function () { loadPathDir($('pathInput').value.trim()); };
    $('pathDriveSel').onchange = function () { loadPathDir(this.value); };
    $('pathInput').onkeydown = function (e) {
      if (e.key === 'Enter') {
        e.preventDefault();
        loadPathDir(this.value.trim());
      }
    };
    // 发布配置编辑弹窗内的「本机路径发布」：先保存配置，再用弹窗里填写的本机路径发布
    $('btnTargetDeployLocal').onclick = function () {
      if (!current || !currentTarget) {
        toast('请先选择发布配置', 'err');
        return;
      }
      if (currentTarget.type === 'frontend') {
        frontendDeploy(currentTarget, this);
        return;
      }
      var path = $('t-localPath').value.trim();
      if (!path) {
        toast('请填写本机 jar 路径', 'err');
        return;
      }
      localDeploy(currentTarget, path, $('t-restartLocal').checked, this);
    };

    // 发布配置卡片「上传发布」触发隐藏文件选择（后端 jar，自定义部署可多选）
    $('jarFile').onchange = function () {
      var files = this.files ? Array.prototype.slice.call(this.files) : [];
      if (files.length && currentTarget) {
        var restart = $('restartAfterUpload').checked ? 'true' : 'false';
        uploadPickedFiles(files, currentTarget.id, restart);
      }
      this.value = '';
    };
    // 前端类型「上传发布」触发隐藏文件选择（zip / 单文件，服务端按配置类型分流）
    $('feUploadFile').onchange = function () {
      if (this.files && this.files[0]) {
        uploadDeploy(this.files[0]);
        this.value = '';
      }
    };
    // 发布配置卡片「一键发布」触发隐藏文件选择，选中后自动走完整发布流程（上传→备份→停止→启动）
    $('oneDeployJar').onchange = function () {
      var files = this.files ? Array.prototype.slice.call(this.files) : [];
      var tid = this.dataset.target;
      if (files.length && tid) {
        uploadPickedFiles(files, tid, 'true');
      }
      this.value = '';
      delete this.dataset.target;
    };
    $('btnLoadLog').onclick = function () {
      loadLog(this);
    };
    // 运行日志：info / error 页签切换（默认 info）
    Array.prototype.forEach.call(document.querySelectorAll('#logTypeSwitch button'), function (b) {
      b.onclick = function () {
        setLogType(b.getAttribute('data-logtype'));
        loadLog();
      };
    });
    // 关键词检索：回车即查询；多个关键词用空格分隔（AND），含空格的词用双引号包裹
    var logKw = $('logKeyword');
    if (logKw) {
      logKw.onkeydown = function (e) {
        if (e.key === 'Enter') {
          e.preventDefault();
          loadLog();
        }
      };
    }
    var logKwReset = $('btnResetLogKeyword');
    if (logKwReset) {
      logKwReset.onclick = function () {
        if (logKw) { logKw.value = ''; }
        loadLog();
      };
    }
    $('btnLoadBackup').onclick = function () {
      loadBackups(this);
    };
    $('btnLoadOpLog').onclick = function () {
      loadOpLogs(this);
    };

    Array.prototype.forEach.call(document.querySelectorAll('.panel-head button[data-act]'), function (btn) {
      btn.onclick = function () {
        var act = btn.getAttribute('data-act');
        if (act === 'test') {
          testConnect(btn);
        } else if (act === 'status') {
          refreshStatus(btn);
        } else         if (act === 'edit') {
          getJson('/api/servers/' + current.id).then(openModal).catch(function (e) {
            toast(e.message, 'err');
          });
        } else if (act === 'installJdk') {
          openJdkModal();
        } else if (act === 'sftp') {
          toggleSftp();
        } else if (act === 'board') {
          window.open('board.html?serverId=' + encodeURIComponent(current.id), '_blank');
        } else if (act === 'del') {
          deleteServer();
        }
      };
    });

    Array.prototype.forEach.call(document.querySelectorAll('.tabbar button'), function (btn) {
      btn.onclick = function () {
        switchTab(btn.getAttribute('data-tab'));
      };
    });

    $('autoLog').onchange = function () {
      if (this.checked) {
        autoLogTimer = setInterval(function () {
          loadLog();
        }, 5000);
      } else if (autoLogTimer) {
        clearInterval(autoLogTimer);
        autoLogTimer = null;
      }
    };

    // SFTP 文件浏览面板
    $('btnSftpRefresh').onclick = function () {
      loadSftp(sftpCurrentPath);
    };
    $('btnSftpClose').onclick = function () {
      $('sftpPanel').style.display = 'none';
    };
    $('btnSftpUpload').onclick = function () {
      $('sftpFile').click();
    };
    $('sftpFile').onchange = function () {
      if (this.files && this.files[0]) {
        sftpUpload(this.files[0]);
        this.value = '';
      }
    };
    $('btnSftpMkdir').onclick = sftpMkdir;

    // vi 远程文件编辑器
    $('btnViWq').onclick = function () {
      viExec('saveQuit');
    };
    $('btnViW').onclick = function () {
      viExec('save');
    };
    $('btnViQuit').onclick = function () {
      viExec('quit');
    };
    $('btnCloseVi').onclick = function () {
      viExec('quit');
    };
    $('viCmdInput').addEventListener('keydown', function (e) {
      if (e.key === 'Enter' || e.keyCode === 13) {
        e.preventDefault();
        var v = this.value.trim();
        if (!v) {
          return;
        }
        if (v === 'wq!' || v === 'wq' || v === 'x' || v === 'ZZ') {
          viExec('saveQuit');
        } else if (v === 'w') {
          viExec('save');
        } else if (v === 'q!' || v === 'q') {
          viExec('quit');
        } else {
          toast('未知 vi 命令: ' + v, 'err');
        }
      } else if (e.key === 'Escape') {
        this.blur();
      }
    });
    $('viEditor').addEventListener('input', function () {
      viDirty = true;
    });
    $('viEditor').addEventListener('keydown', function (e) {
      if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
        e.preventDefault();
        viExec('saveQuit');
      }
    });

    // 命令行执行（终端内回车执行，↑/↓ 翻历史）
    $('btnClearCmd').onclick = clearCommand;
    $('cmdInput').addEventListener('keydown', function (e) {
      if (e.key === 'Enter' || e.keyCode === 13) {
        e.preventDefault();
        execCommand();
      } else if (e.key === 'Tab' || e.keyCode === 9) {
        e.preventDefault();
        cmdComplete();
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        cmdHistUp();
      } else if (e.key === 'ArrowDown') {
        e.preventDefault();
        cmdHistDown();
      }
    });
    // 用户继续输入时隐藏补全候选列表
    $('cmdInput').addEventListener('input', function () {
      hideCmdComplete();
    });

    // SFTP 面板宽度拖拽（手柄在左侧边缘）
    initSftpResizer();
  });

  // 拖拽调整右侧 SFTP 面板宽度，宽度记忆到 localStorage
  function initSftpResizer() {
    var panel = $('sftpPanel');
    var handle = $('sftpResizer');
    if (!panel || !handle) {
      return;
    }
    var saved = parseInt(localStorage.getItem('sftpPanelWidth'), 10);
    if (saved >= 280 && saved <= 820) {
      panel.style.flex = '0 0 ' + saved + 'px';
      panel.style.width = saved + 'px';
    }
    var startX = 0, startW = 0, dragging = false;
    handle.addEventListener('mousedown', function (e) {
      dragging = true;
      startX = e.clientX;
      startW = panel.getBoundingClientRect().width;
      document.body.classList.add('sftp-resizing');
      e.preventDefault();
    });
    document.addEventListener('mousemove', function (e) {
      if (!dragging) {
        return;
      }
      var w = startW + (startX - e.clientX);
      w = Math.max(280, Math.min(820, w));
      panel.style.flex = '0 0 ' + w + 'px';
      panel.style.width = w + 'px';
    });
    document.addEventListener('mouseup', function () {
      if (!dragging) {
        return;
      }
      dragging = false;
      document.body.classList.remove('sftp-resizing');
      localStorage.setItem('sftpPanelWidth', Math.round(panel.getBoundingClientRect().width));
    });
  }

  // ------------------------------------------------------------ Nacos 集群部署

  var nacosTaskSteps = [];
  var nacosFinished = false;

  function openNacosModal() {
    $('nacosClusterName').value = 'nacos-cluster';
    $('nacosPort').value = '8848';
    $('nacosInstallDir').value = '/opt/nacos';
    $('kbPort').value = '54321';
    $('kbDb').value = 'nacos_config';
    $('nacosJdbcDriver').value = 'com.kingbase8.Driver';
    $('nacosJdbcUrl').value = '';
    $('nacosExtraProps').value = '';
    $('nacosFile').value = '';
    $('kbHost').value = '';
    $('kbUser').value = '';
    $('kbPassword').value = '';
    $('nacosProgress').style.display = 'none';
    $('nacosNodeList').innerHTML = '<div class="nacos-loading">加载服务器列表…</div>';
    $('nacosModalMask').classList.add('show');
    loadServersIntoNacos();
  }

  function closeNacosModal() {
    $('nacosModalMask').classList.remove('show');
  }

  function loadServersIntoNacos() {
    request('/api/servers').then(function (list) {
      if (!list || list.length === 0) {
        $('nacosNodeList').innerHTML = '<div class="nacos-loading">暂无服务器，请先在左侧新增服务器</div>';
        return;
      }
      var html = list.map(function (s) {
        return '<label class="nacos-node"><input type="checkbox" class="nacos-node-cb" value="' + s.id + '">'
          + '<span class="nacos-node-name">' + escapeHtml(s.name) + '</span>'
          + '<span class="nacos-node-meta">' + escapeHtml((s.username || '') + '@' + (s.host || '') + ':' + (s.port || 22)) + '</span></label>';
      }).join('');
      $('nacosNodeList').innerHTML = html;
      $('nacosSelAll').onchange = function () {
        var cbs = $('nacosNodeList').querySelectorAll('.nacos-node-cb');
        for (var i = 0; i < cbs.length; i++) {
          cbs[i].checked = this.checked;
        }
      };
    }).catch(function (e) {
      $('nacosNodeList').innerHTML = '<div class="nacos-loading">加载失败: ' + escapeHtml(e.message || '') + '</div>';
    });
  }

  function nacosDeploy() {
    var nodes = [];
    var cbs = $('nacosNodeList').querySelectorAll('.nacos-node-cb');
    for (var i = 0; i < cbs.length; i++) {
      if (cbs[i].checked) {
        nodes.push(cbs[i].value);
      }
    }
    if (nodes.length < 2) {
      toast('请至少勾选 2 个节点服务器', 'err');
      return;
    }
    var file = $('nacosFile').files[0];
    if (!file) {
      toast('请选择 nacos-server 安装包', 'err');
      return;
    }
    if (!$('kbHost').value.trim() || !$('kbUser').value.trim() || !$('kbPassword').value) {
      toast('请填写人大金仓数据库的主机 / 账号 / 密码', 'err');
      return;
    }
    var fd = new FormData();
    fd.append('file', file);
    for (var n = 0; n < nodes.length; n++) {
      fd.append('nodeIds', nodes[n]);
    }
    fd.append('clusterName', $('nacosClusterName').value);
    fd.append('nacosPort', $('nacosPort').value);
    fd.append('installDir', $('nacosInstallDir').value);
    fd.append('kbHost', $('kbHost').value.trim());
    fd.append('kbPort', $('kbPort').value);
    fd.append('kbDb', $('kbDb').value.trim());
    fd.append('kbUser', $('kbUser').value.trim());
    fd.append('kbPassword', $('kbPassword').value);
    fd.append('jdbcDriver', $('nacosJdbcDriver').value.trim());
    fd.append('jdbcUrl', $('nacosJdbcUrl').value.trim());
    fd.append('extraProps', $('nacosExtraProps').value);

    $('btnDeployNacos').disabled = true;
    xhrUpload('/api/nacos/deploy', fd, function (pct, loaded, total, speed) {
      $('nacosUploadBox').style.display = 'block';
      $('nacosUploadBar').style.width = pct + '%';
      $('nacosUploadPct').textContent = pct + '%';
      $('nacosUploadSpeed').textContent = (speed > 0 ? formatSize(speed) + '/s' : '');
    }).then(function (taskId) {
      $('nacosUploadBox').style.display = 'none';
      nacosSubscribe(taskId);
    }).catch(function (e) {
      $('btnDeployNacos').disabled = false;
      $('nacosUploadBox').style.display = 'none';
      toast(e && e.message || '部署请求失败', 'err');
    });
  }

  /** 订阅 Nacos 部署任务的 SSE 进度，渲染到弹窗内的进度区（独立于服务器结果面板） */
  function nacosSubscribe(taskId) {
    nacosFinished = false;
    nacosTaskSteps = [];
    $('nacosProgress').style.display = 'block';
    $('nacosBanner').style.display = 'none';
    $('nacosOverallBar').style.width = '0%';
    $('nacosOverallText').textContent = '准备中...';
    $('nacosStepList').innerHTML = '';
    var es = new EventSource('/api/deploy/tasks/' + taskId + '/stream');
    es.addEventListener('snapshot', function (ev) {
      try {
        nacosRenderSteps(JSON.parse(ev.data).steps);
      } catch (e) { /* ignore */ }
    });
    es.addEventListener('step', function (ev) {
      try {
        nacosUpdateStep(JSON.parse(ev.data));
      } catch (e) { /* ignore */ }
    });
    es.addEventListener('done', function (ev) {
      nacosFinished = true;
      try {
        nacosRenderResult(JSON.parse(ev.data));
      } catch (e) { /* ignore */ }
      es.close();
      $('btnDeployNacos').disabled = false;
    });
    es.addEventListener('conn-error', function (ev) {
      if (nacosFinished) {
        return;
      }
      nacosFinished = true;
      es.close();
      nacosOnError('任务不存在或已过期，请重新执行操作');
      $('btnDeployNacos').disabled = false;
    });
    es.addEventListener('error', function () {
      if (nacosFinished) {
        return;
      }
      nacosFinished = true;
      es.close();
      nacosOnError('与服务器的连接中断，未收到执行结果');
      $('btnDeployNacos').disabled = false;
    });
  }

  function nacosRenderSteps(steps) {
    nacosTaskSteps = steps.map(function (s) { return s; });
    $('nacosStepList').innerHTML = nacosTaskSteps.map(nacosStepHtml).join('');
    nacosUpdateOverall();
  }

  function nacosStepHtml(s) {
    var cls = s.status === 'RUNNING' ? 'running'
      : (s.status === 'SUCCESS' ? 'ok' : (s.status === 'FAIL' ? 'no' : 'pending'));
    var ico = s.status === 'RUNNING' ? '<span class="spinner"></span>'
      : (s.status === 'SUCCESS' ? '✓' : (s.status === 'FAIL' ? '✗' : '•'));
    var bar = (s.status === 'RUNNING' && s.progress > 0)
      ? '<div class="progress"><div class="progress-bar" style="width:' + s.progress + '%"></div>'
        + '<span class="progress-pct">' + s.progress + '%</span></div>' : '';
    return '<li class="nacos-step nacos-step-' + s.index + ' ' + cls + '">'
      + '<span class="ico">' + ico + '</span>'
      + '<span class="name">' + escapeHtml(s.name) + '</span>'
      + '<span class="detail">' + escapeHtml(s.detail || '') + '</span>'
      + bar + '</li>';
  }

  function nacosUpdateStep(s) {
    nacosTaskSteps[s.index] = s;
    var li = document.querySelector('.nacos-step-' + s.index);
    if (!li) {
      return;
    }
    var cls = s.status === 'RUNNING' ? 'running'
      : (s.status === 'SUCCESS' ? 'ok' : (s.status === 'FAIL' ? 'no' : 'pending'));
    li.className = 'nacos-step nacos-step-' + s.index + ' ' + cls;
    li.querySelector('.ico').innerHTML = s.status === 'RUNNING' ? '<span class="spinner"></span>'
      : (s.status === 'SUCCESS' ? '✓' : (s.status === 'FAIL' ? '✗' : '•'));
    if (s.detail) {
      li.querySelector('.detail').textContent = s.detail;
    }
    var bar = li.querySelector('.progress');
    if (s.status === 'RUNNING' && s.progress > 0) {
      if (!bar) {
        bar = document.createElement('div');
        bar.className = 'progress';
        bar.innerHTML = '<div class="progress-bar"></div><span class="progress-pct"></span>';
        li.appendChild(bar);
      }
      bar.querySelector('.progress-bar').style.width = s.progress + '%';
      bar.querySelector('.progress-pct').textContent = s.progress + '%';
    } else if (bar) {
      li.removeChild(bar);
    }
    nacosUpdateOverall();
  }

  function nacosUpdateOverall() {
    var total = nacosTaskSteps.length;
    if (total === 0) {
      return;
    }
    var done = 0;
    for (var i = 0; i < total; i++) {
      var s = nacosTaskSteps[i];
      if (!s) {
        continue;
      }
      if (s.status === 'SUCCESS' || s.status === 'FAIL') {
        done += 1;
      } else if (s.status === 'RUNNING') {
        done += 0.5;
      }
    }
    var pct = Math.round(done / total * 100);
    $('nacosOverallBar').style.width = pct + '%';
    $('nacosOverallText').textContent = '总进度 ' + pct + '%';
  }

  function nacosRenderResult(r) {
    var html = (r.steps || []).map(function (s) {
      var skipped = (!s.success && !s.detail);
      var cls = skipped ? 'skip' : (s.success ? 'ok' : 'no');
      var ico = skipped ? '–' : (s.success ? '✓' : '✗');
      return '<li class="' + cls + '"><span class="ico ' + cls + '">' + ico + '</span>'
        + '<span class="name">' + escapeHtml(s.name) + '</span>'
        + '<span class="detail">' + escapeHtml(skipped ? '未执行（前序步骤失败，已中止）' : s.detail) + '</span>'
        + '<span class="cost">' + s.costMs + 'ms</span></li>';
    }).join('');
    $('nacosStepList').innerHTML = html;
    var banner = $('nacosBanner');
    if (r.success) {
      banner.style.display = 'none';
    } else {
      banner.style.display = 'block';
      banner.className = 'result-banner err';
      banner.textContent = '❌ 执行失败：' + (r.message || '未知错误');
    }
    toast(r.message + '（' + (r.costMs || 0) + 'ms）', r.success ? 'ok' : 'err');
  }

  function nacosOnError(msg) {
    var banner = $('nacosBanner');
    banner.style.display = 'block';
    banner.className = 'result-banner err';
    banner.textContent = '❌ ' + msg;
    toast(msg, 'err');
  }

  // ------------------------------------------------------------ 磁盘管理

  var diskTaskSteps = [];
  var diskFinished = false;

  function openDiskModal() {
    $('diskProgress').style.display = 'none';
    $('diskListWrap').style.display = 'none';
    $('diskMpList').innerHTML = '';
    $('diskMountCfg').style.display = 'none';
    $('diskScanHint').textContent = '';
    $('diskRawFdisk').textContent = '';
    $('btnMountDisk').disabled = true;
    request('/api/servers').then(function (list) {
      var sel = $('diskServerSel');
      if (!list || list.length === 0) {
        sel.innerHTML = '<option value="">（暂无服务器，请先新增）</option>';
        return;
      }
      sel.innerHTML = list.map(function (s) {
        return '<option value="' + s.id + '">' + escapeHtml(s.name) + ' (' + escapeHtml(s.host || '') + ')</option>';
      }).join('');
      resetDiskScan();
    }).catch(function (e) {
      $('diskScanHint').textContent = '加载服务器失败: ' + e.message;
    });
    $('diskModalMask').classList.add('show');
  }

  function closeDiskModal() {
    $('diskModalMask').classList.remove('show');
  }

  // ------------------------------------------------------------ 批量修改发布目录（前缀替换）
  function openBatchDir() {
    if (!current) {
      toast('请先选择一台服务器', 'err');
      return;
    }
    var ids = selectedBatchIds();
    if (!ids.length) {
      toast('请先在发布配置前勾选要修改的项', 'err');
      return;
    }
    $('batchDirOld').value = '';
    $('batchDirNew').value = '';
    $('batchDirCount').textContent = '已勾选 ' + ids.length + ' 个发布配置，仅其中发布目录以「原前缀」开头的项会被替换。';
    $('batchDirModalMask').classList.add('show');
    setTimeout(function () { $('batchDirOld').focus(); }, 50);
  }

  function closeBatchDirModal() {
    $('batchDirModalMask').classList.remove('show');
  }

  /** 收集当前勾选的发布配置 id 数组 */
  function selectedBatchIds() {
    var ids = [];
    for (var id in selectedTargetIds) {
      if (selectedTargetIds.hasOwnProperty(id) && selectedTargetIds[id]) {
        ids.push(id);
      }
    }
    return ids;
  }

  function confirmBatchDir() {
    var ids = selectedBatchIds();
    if (!ids.length) {
      toast('请先勾选要修改的发布配置', 'err');
      return;
    }
    var oldPrefix = $('batchDirOld').value.trim();
    var newPrefix = $('batchDirNew').value.trim();
    if (!oldPrefix) {
      toast('请填写原前缀', 'err');
      return;
    }
    if (!newPrefix) {
      toast('请填写新前缀', 'err');
      return;
    }
    var btn = $('btnConfirmBatchDir');
    btn.disabled = true;
    btn.textContent = '替换中…';
    postJson('/api/servers/' + encodeURIComponent(current.id) + '/targets/batch-remote-dir',
        { ids: ids, oldPrefix: oldPrefix, newPrefix: newPrefix })
      .then(function (res) {
        var changed = res && res.changed != null ? res.changed : 0;
        if (changed > 0) {
          toast('已替换 ' + changed + ' 个发布目录', 'ok');
        } else {
          toast('没有发布目录命中前缀「' + oldPrefix + '」，未做修改', 'err');
        }
        closeBatchDirModal();
        loadTargets(current.id, true);
      })
      .catch(function (e) {
        toast((e && e.message) || '批量修改失败', 'err');
      })
      .finally(function () {
        btn.disabled = false;
        btn.textContent = '开始替换';
      });
  }

  // ------------------------------------------------------------ 批量修改 JVM 参数
  function openBatchJvm() {
    if (!current) {
      toast('请先选择一台服务器', 'err');
      return;
    }
    var ids = selectedBatchIds();
    if (!ids.length) {
      toast('请先在发布配置前勾选要修改的项', 'err');
      return;
    }
    $('batchJvmOpts').value = '';
    $('batchJvmModeOverwrite').checked = true;
    $('batchJvmCount').textContent = '已勾选 ' + ids.length + ' 个发布配置；仅其中「后端」类型会被修改，前端配置自动跳过。';
    $('batchJvmModalMask').classList.add('show');
    setTimeout(function () { $('batchJvmOpts').focus(); }, 50);
  }

  function closeBatchJvmModal() {
    $('batchJvmModalMask').classList.remove('show');
  }

  function confirmBatchJvm() {
    var ids = selectedBatchIds();
    if (!ids.length) {
      toast('请先勾选要修改的发布配置', 'err');
      return;
    }
    var jvmOpts = $('batchJvmOpts').value.trim();
    var mode = $('batchJvmModeAppend') && $('batchJvmModeAppend').checked ? 'append' : 'overwrite';
    var btn = $('btnConfirmBatchJvm');
    btn.disabled = true;
    btn.textContent = '修改中…';
    postJson('/api/servers/' + encodeURIComponent(current.id) + '/targets/batch-jvm',
        { ids: ids, jvmOpts: jvmOpts, mode: mode })
      .then(function (res) {
        var changed = res && res.changed != null ? res.changed : 0;
        var skipped = res && res.skipped != null ? res.skipped : 0;
        if (changed > 0) {
          var msg = '已修改 ' + changed + ' 个后端配置的 JVM 参数';
          if (skipped > 0) { msg += '，跳过 ' + skipped + ' 个前端配置'; }
          toast(msg, 'ok');
        } else if (skipped > 0) {
          toast('勾选的全是前端配置，已跳过（前端无 JVM 参数）', 'err');
        } else {
          toast('所选后端配置的 JVM 参数无需变更（内容已一致）', 'err');
        }
        closeBatchJvmModal();
        loadTargets(current.id, true);
      })
      .catch(function (e) {
        toast((e && e.message) || '批量修改失败', 'err');
      })
      .finally(function () {
        btn.disabled = false;
        btn.textContent = '开始修改';
      });
  }

  // ------------------------------------------------------------ 免密模式
  // 登录页与登录入口已移除，以下函数保留为空实现以兼容可能的外部调用
  function refreshAuthStatus() { /* 免密模式：无需刷新登录状态 */ }
  function toggleAuth() { /* 免密模式：登录开关已移除 */ }
  function doLogout() { /* 免密模式：无需退出登录 */ }

  function resetDiskScan() {
    $('diskListWrap').style.display = 'none';
    $('diskMpList').innerHTML = '';
    $('diskMountCfg').style.display = 'none';
    $('diskScanHint').textContent = '';
    $('diskRawFdisk').textContent = '';
    $('btnMountDisk').disabled = true;
  }

  function diskScan() {
    var sid = $('diskServerSel').value;
    if (!sid) {
      toast('请先选择服务器', 'err');
      return;
    }
    $('diskScanHint').textContent = '扫描中...';
    $('diskListWrap').style.display = 'none';
    request('/api/disk/scan?serverId=' + encodeURIComponent(sid)).then(function (res) {
      renderDiskList(res);
      var n = res.disks ? res.disks.filter(function (d) { return d.mountable; }).length : 0;
      $('diskScanHint').textContent = '共 ' + (res.disks ? res.disks.length : 0) + ' 块磁盘，' + n + ' 块可挂载';
    }).catch(function (e) {
      $('diskScanHint').textContent = '扫描失败: ' + e.message;
    });
  }

  function renderDiskList(res) {
    var body = $('diskBody');
    if (!res.disks || res.disks.length === 0) {
      body.innerHTML = '<tr><td colspan="7" class="empty">未检测到磁盘</td></tr>';
    } else {
      body.innerHTML = res.disks.map(function (d) {
        var mountable = d.mountable;
        var cb = mountable
          ? '<input type="checkbox" class="disk-cb" value="' + escapeHtml(d.device) + '">'
          : '<input type="checkbox" disabled title="' + escapeHtml(d.reason) + '">';
        var statusCls = mountable ? 'ok' : 'no';
        var statusText = mountable ? '可挂载' : '不可挂载';
        return '<tr data-mountable="' + (mountable ? 1 : 0) + '">'
          + '<td>' + cb + '</td>'
          + '<td>' + escapeHtml(d.device) + '</td>'
          + '<td>' + escapeHtml(d.sizeHuman || '') + '</td>'
          + '<td>' + escapeHtml(d.model || '') + '</td>'
          + '<td>' + escapeHtml(d.fstype || '—') + '</td>'
          + '<td>' + escapeHtml(d.mountpoint || '—') + '</td>'
          + '<td class="' + statusCls + '">' + statusText
          + '<div class="hint">' + escapeHtml(d.reason) + '</div></td>'
          + '</tr>';
      }).join('');
      var cbs = body.querySelectorAll('.disk-cb');
      for (var i = 0; i < cbs.length; i++) {
        cbs[i].onchange = syncDiskSelection;
      }
    }
    $('diskRawFdisk').textContent = res.rawFdisk || '(无输出)';
    $('diskListWrap').style.display = 'block';
    $('diskSelAll').checked = false;
    syncDiskSelection();
  }

  function syncDiskSelection() {
    var cbs = $('diskBody').querySelectorAll('.disk-cb');
    var sel = [];
    for (var i = 0; i < cbs.length; i++) {
      if (cbs[i].checked) {
        sel.push(cbs[i].value);
      }
    }
    if (sel.length === 0) {
      $('diskMountCfg').style.display = 'none';
      $('diskMpList').innerHTML = '';
      $('btnMountDisk').disabled = true;
      return;
    }
    $('diskMountCfg').style.display = 'block';
    var list = $('diskMpList');
    list.innerHTML = sel.map(function (dev, i) {
      var mp = (i === 0) ? '/data' : '/data' + i;
      return '<div class="disk-mp-item"><span class="disk-mp-dev">' + escapeHtml(dev) + '</span>'
        + '<span class="disk-mp-arrow">→</span>'
        + '<input type="text" class="disk-mp-input" data-dev="' + escapeHtml(dev) + '" value="' + mp + '"></div>';
    }).join('');
    $('btnMountDisk').disabled = false;
  }

  function diskMount() {
    var sid = $('diskServerSel').value;
    if (!sid) {
      toast('请先选择服务器', 'err');
      return;
    }
    var inputs = $('diskMpList').querySelectorAll('.disk-mp-input');
    if (inputs.length === 0) {
      toast('请先勾选要挂载的磁盘', 'err');
      return;
    }
    var disks = [];
    for (var i = 0; i < inputs.length; i++) {
      var mp = inputs[i].value.trim();
      if (!mp) {
        toast('挂载点不能为空: ' + inputs[i].getAttribute('data-dev'), 'err');
        return;
      }
      disks.push({ device: inputs[i].getAttribute('data-dev'), mountPoint: mp });
    }
    var body = {
      serverId: sid,
      fsType: $('diskFsType').value,
      addFstab: $('diskAddFstab').checked,
      disks: disks
    };
    $('btnMountDisk').disabled = true;
    postJson('/api/disk/mount', body).then(function (task) {
      diskSubscribe(task.taskId);
    }).catch(function (e) {
      $('btnMountDisk').disabled = false;
      toast(e.message || '挂载请求失败', 'err');
    });
  }

  /** 订阅磁盘挂载任务的 SSE 进度，渲染到弹窗内的进度区（独立于服务器结果面板） */
  function diskSubscribe(taskId) {
    diskFinished = false;
    diskTaskSteps = [];
    $('diskProgress').style.display = 'block';
    $('diskBanner').style.display = 'none';
    $('diskOverallBar').style.width = '0%';
    $('diskOverallText').textContent = '准备中...';
    $('diskStepList').innerHTML = '';
    var es = new EventSource('/api/deploy/tasks/' + taskId + '/stream');
    es.addEventListener('snapshot', function (ev) {
      try {
        diskRenderSteps(JSON.parse(ev.data).steps);
      } catch (e) { /* ignore */ }
    });
    es.addEventListener('step', function (ev) {
      try {
        diskUpdateStep(JSON.parse(ev.data));
      } catch (e) { /* ignore */ }
    });
    es.addEventListener('done', function (ev) {
      diskFinished = true;
      try {
        diskRenderResult(JSON.parse(ev.data));
      } catch (e) { /* ignore */ }
      es.close();
      $('btnMountDisk').disabled = false;
    });
    es.addEventListener('conn-error', function (ev) {
      if (diskFinished) {
        return;
      }
      diskFinished = true;
      es.close();
      diskOnError('任务不存在或已过期，请重新执行操作');
      $('btnMountDisk').disabled = false;
    });
    es.addEventListener('error', function () {
      if (diskFinished) {
        return;
      }
      diskFinished = true;
      es.close();
      diskOnError('与服务器的连接中断，未收到执行结果');
      $('btnMountDisk').disabled = false;
    });
  }

  function diskRenderSteps(steps) {
    diskTaskSteps = steps.map(function (s) { return s; });
    $('diskStepList').innerHTML = diskTaskSteps.map(diskStepHtml).join('');
    diskUpdateOverall();
  }

  function diskStepHtml(s) {
    var cls = s.status === 'RUNNING' ? 'running'
      : (s.status === 'SUCCESS' ? 'ok' : (s.status === 'FAIL' ? 'no' : 'pending'));
    var ico = s.status === 'RUNNING' ? '<span class="spinner"></span>'
      : (s.status === 'SUCCESS' ? '✓' : (s.status === 'FAIL' ? '✗' : '•'));
    var bar = (s.status === 'RUNNING' && s.progress > 0)
      ? '<div class="progress"><div class="progress-bar" style="width:' + s.progress + '%"></div>'
        + '<span class="progress-pct">' + s.progress + '%</span></div>' : '';
    return '<li class="disk-step disk-step-' + s.index + ' ' + cls + '">'
      + '<span class="ico">' + ico + '</span>'
      + '<span class="name">' + escapeHtml(s.name) + '</span>'
      + '<span class="detail">' + escapeHtml(s.detail || '') + '</span>'
      + bar + '</li>';
  }

  function diskUpdateStep(s) {
    diskTaskSteps[s.index] = s;
    var li = document.querySelector('.disk-step-' + s.index);
    if (!li) {
      return;
    }
    var cls = s.status === 'RUNNING' ? 'running'
      : (s.status === 'SUCCESS' ? 'ok' : (s.status === 'FAIL' ? 'no' : 'pending'));
    li.className = 'disk-step disk-step-' + s.index + ' ' + cls;
    li.querySelector('.ico').innerHTML = s.status === 'RUNNING' ? '<span class="spinner"></span>'
      : (s.status === 'SUCCESS' ? '✓' : (s.status === 'FAIL' ? '✗' : '•'));
    if (s.detail) {
      li.querySelector('.detail').textContent = s.detail;
    }
    var bar = li.querySelector('.progress');
    if (s.status === 'RUNNING' && s.progress > 0) {
      if (!bar) {
        bar = document.createElement('div');
        bar.className = 'progress';
        bar.innerHTML = '<div class="progress-bar"></div><span class="progress-pct"></span>';
        li.appendChild(bar);
      }
      bar.querySelector('.progress-bar').style.width = s.progress + '%';
      bar.querySelector('.progress-pct').textContent = s.progress + '%';
    } else if (bar) {
      li.removeChild(bar);
    }
    diskUpdateOverall();
  }

  function diskUpdateOverall() {
    var total = diskTaskSteps.length;
    if (total === 0) {
      return;
    }
    var done = 0;
    for (var i = 0; i < total; i++) {
      var s = diskTaskSteps[i];
      if (!s) {
        continue;
      }
      if (s.status === 'SUCCESS' || s.status === 'FAIL') {
        done += 1;
      } else if (s.status === 'RUNNING') {
        done += 0.5;
      }
    }
    var pct = Math.round(done / total * 100);
    $('diskOverallBar').style.width = pct + '%';
    $('diskOverallText').textContent = '总进度 ' + pct + '%';
  }

  function diskRenderResult(r) {
    var html = (r.steps || []).map(function (s) {
      var skipped = (!s.success && !s.detail);
      var cls = skipped ? 'skip' : (s.success ? 'ok' : 'no');
      var ico = skipped ? '–' : (s.success ? '✓' : '✗');
      return '<li class="' + cls + '"><span class="ico ' + cls + '">' + ico + '</span>'
        + '<span class="name">' + escapeHtml(s.name) + '</span>'
        + '<span class="detail">' + escapeHtml(skipped ? '未执行（前序步骤失败，已中止）' : s.detail) + '</span>'
        + '<span class="cost">' + s.costMs + 'ms</span></li>';
    }).join('');
    $('diskStepList').innerHTML = html;
    var banner = $('diskBanner');
    if (r.success) {
      banner.style.display = 'none';
    } else {
      banner.style.display = 'block';
      banner.className = 'result-banner err';
      banner.textContent = '❌ 执行失败：' + (r.message || '未知错误');
    }
    toast(r.message + '（' + (r.costMs || 0) + 'ms）', r.success ? 'ok' : 'err');
  }

  function diskOnError(msg) {
    var banner = $('diskBanner');
    banner.style.display = 'block';
    banner.className = 'result-banner err';
    banner.textContent = '❌ ' + msg;
    toast(msg, 'err');
  }
})();
