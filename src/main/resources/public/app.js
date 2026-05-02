let sessionId = '';

marked.setOptions({ breaks: true, gfm: true });

function newSession() {
    sessionId = crypto.randomUUID();
    document.getElementById('session-id').value = sessionId;
    document.getElementById('chat-box').innerHTML =
        '<div class="chat-empty"><strong>Edge AI Gateway</strong>Start a conversation below</div>';
}

function setStatus(state) {
    const pill = document.getElementById('status-pill');
    pill.className = 'status-pill ' + state;
    pill.textContent = state === 'streaming' ? 'Streaming…' : 'Ready';
}

function setInputEnabled(enabled) {
    document.getElementById('send').disabled = !enabled;
    document.getElementById('input').disabled = !enabled;
}

function appendUserMessage(text) {
    const chatBox = document.getElementById('chat-box');
    const empty = chatBox.querySelector('.chat-empty');
    if (empty) empty.remove();

    const msg = document.createElement('div');
    msg.className = 'msg user';
    msg.innerHTML = `<div class="msg-label">You</div><div class="msg-bubble">${escapeHtml(text)}</div>`;
    chatBox.appendChild(msg);
    scrollToBottom();
}

function appendAgentMessage(name, index, pending) {
    const chatBox = document.getElementById('chat-box');
    const msg = document.createElement('div');
    msg.className = `msg model agent-turn ${agentStageClass(index, name)}` + (pending ? ' streaming-bubble' : '');
    msg.innerHTML = `
        <div class="msg-label">
            <span class="agent-avatar">${agentInitial(name)}</span>
            <span class="agent-meta">
                <span class="agent-name">${escapeHtml(name)}</span>
                <span class="agent-stage">${escapeHtml(agentStageLabel(index, name))}</span>
            </span>
        </div>
        <div class="msg-bubble markdown"></div>`;
    chatBox.appendChild(msg);
    scrollToBottom();
    return msg;
}

function updateAgentMessage(msg, name, index, display) {
    msg.classList.remove('streaming-bubble');
    msg.classList.remove('agent-planning', 'agent-research', 'agent-answer');
    msg.classList.add(agentStageClass(index, name));
    msg.querySelector('.agent-avatar').textContent = agentInitial(name);
    msg.querySelector('.agent-name').textContent = name;
    msg.querySelector('.agent-stage').textContent = agentStageLabel(index, name);
    const bubble = msg.querySelector('.msg-bubble');
    bubble.innerHTML = marked.parse(display || '');
    scrollToBottom();
    return bubble;
}

function finalizeAgentMessage(msg, fullText) {
    msg.classList.remove('streaming-bubble');
    msg.querySelector('.msg-bubble').innerHTML = marked.parse(fullText || '');
    scrollToBottom();
}

function agentInitial(name) {
    return (name || 'A').trim().charAt(0).toUpperCase();
}

function agentStageLabel(index, name) {
    if (index === 0) return 'Planning';
    if ((name || '').toLowerCase().includes('research')) return 'Research';
    return 'Final answer';
}

function agentStageClass(index, name) {
    if (index === 0) return 'agent-planning';
    if ((name || '').toLowerCase().includes('research')) return 'agent-research';
    return 'agent-answer';
}

function scrollToBottom() {
    const chatBox = document.getElementById('chat-box');
    chatBox.scrollTop = chatBox.scrollHeight;
}

function escapeHtml(text) {
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function sendMessage() {
    const input = document.getElementById('input');
    const text = input.value.trim();
    if (!text) return;
    if (!sessionId) newSession();

    input.value = '';
    input.style.height = 'auto';
    setInputEnabled(false);
    setStatus('streaming');

    appendUserMessage(text);
    const pendingMessage = appendAgentMessage('Assistant', 0, true);
    let fullText = '';
    let finalMessage = pendingMessage;
    let agentEventCount = 0;

    const params = encodeURIComponent(JSON.stringify({session_id: sessionId, message: text}));
    const es = new EventSource('/api/agent/chat/stream?body=' + params);

    es.addEventListener('agent', e => {
        try {
            const obj = JSON.parse(e.data);
            const display = obj.display ?? obj.content;
            if (display !== undefined && display !== null) {
                const agentName = obj.name || `Agent ${obj.index + 1}`;
                if (agentEventCount === 0) {
                    finalMessage = pendingMessage;
                } else {
                    finalMessage = appendAgentMessage(agentName, obj.index, false);
                }
                agentEventCount++;
                fullText = display;
                updateAgentMessage(finalMessage, agentName, obj.index, fullText);
            }
        } catch (_) {}
    });

    es.addEventListener('done', () => {
        es.close();
        finalizeAgentMessage(finalMessage, fullText);
        setStatus('idle');
        setInputEnabled(true);
        document.getElementById('input').focus();
    });

    es.addEventListener('error', e => {
        es.close();
        if (!fullText) updateAgentMessage(pendingMessage, 'Assistant', 0, '[Connection error]');
        pendingMessage.classList.remove('streaming-bubble');
        setStatus('idle');
        setInputEnabled(true);
    });
}

document.addEventListener('DOMContentLoaded', () => {
    newSession();

    const input = document.getElementById('input');

    input.addEventListener('input', () => {
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 200) + 'px';
    });

    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });

    document.getElementById('send').addEventListener('click', sendMessage);

    document.getElementById('new-session').addEventListener('click', newSession);

    document.getElementById('session-id').addEventListener('change', (e) => {
        sessionId = e.target.value.trim();
    });
});
