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

function appendModelMessage() {
    const chatBox = document.getElementById('chat-box');
    const msg = document.createElement('div');
    msg.className = 'msg model streaming-bubble';
    msg.innerHTML = '<div class="msg-label">Assistant</div><div class="msg-bubble"></div>';
    chatBox.appendChild(msg);
    scrollToBottom();
    return msg.querySelector('.msg-bubble');
}

function finalizeModelMessage(bubble, fullText) {
    bubble.parentElement.classList.remove('streaming-bubble');
    bubble.classList.add('markdown');
    bubble.innerHTML = marked.parse(fullText);
    scrollToBottom();
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
    const bubble = appendModelMessage();
    let fullText = '';

    const params = encodeURIComponent(JSON.stringify({session_id: sessionId, message: text}));
    const es = new EventSource('/api/agent/chat/stream?body=' + params);

    es.addEventListener('token', e => {
        try {
            const obj = JSON.parse(e.data);
            if (obj.token) {
                fullText += obj.token;
                bubble.textContent = fullText;
                scrollToBottom();
            }
        } catch (_) {}
    });

    es.addEventListener('done', () => {
        es.close();
        finalizeModelMessage(bubble, fullText);
        setStatus('idle');
        setInputEnabled(true);
        document.getElementById('input').focus();
    });

    es.addEventListener('error', e => {
        es.close();
        if (!fullText) bubble.textContent = '[Connection error]';
        bubble.parentElement.classList.remove('streaming-bubble');
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
