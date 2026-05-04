let sessionId = '';

// References to the active (in-progress) bubble's DOM elements
let toolActivityEl = null;
let responseTextEl = null;
let stageLabelEl   = null;
let tokenBuffer    = '';

marked.setOptions({ breaks: true, gfm: true });

// ── Session ───────────────────────────────────────────────────────────────

function newSession() {
    sessionId = crypto.randomUUID();
    document.getElementById('session-id').value = sessionId;
    document.getElementById('chat-box').innerHTML =
        '<div class="chat-empty"><strong>Edge AI Gateway</strong>Ask anything — I can read files, browse directories, and call APIs.</div>';
    toolActivityEl = null;
    responseTextEl = null;
    stageLabelEl   = null;
    tokenBuffer    = '';
}

// ── Status pill ───────────────────────────────────────────────────────────

function setStatus(state) {
    const pill = document.getElementById('status-pill');
    pill.className = 'status-pill ' + state;
    pill.textContent = { idle: 'Ready', thinking: 'Thinking…', streaming: 'Streaming…' }[state] ?? 'Ready';
}

function setInputEnabled(enabled) {
    document.getElementById('send').disabled  = !enabled;
    document.getElementById('input').disabled = !enabled;
}

// ── Chat messages ─────────────────────────────────────────────────────────

function appendUserMessage(text) {
    const chatBox = document.getElementById('chat-box');
    const empty = chatBox.querySelector('.chat-empty');
    if (empty) empty.remove();

    const msg = document.createElement('div');
    msg.className = 'msg user';
    msg.innerHTML = `<div class="msg-label">You</div>
                     <div class="msg-bubble">${escapeHtml(text)}</div>`;
    chatBox.appendChild(msg);
    scrollToBottom();
}

/**
 * Creates a new assistant bubble and stores references to its inner elements.
 * Uses querySelector on the specific msgEl — NOT getElementById — so multiple
 * bubbles in the thread never interfere with each other.
 */
function createAssistantBubble() {
    const chatBox = document.getElementById('chat-box');
    const msg = document.createElement('div');
    msg.className = 'msg model agent-turn streaming-bubble';
    msg.innerHTML = `
        <div class="msg-label">
            <span class="agent-avatar">A</span>
            <span class="agent-meta">
                <span class="agent-name">Assistant</span>
                <span class="agent-stage">Thinking…</span>
            </span>
        </div>
        <div class="msg-bubble markdown">
            <div class="tool-activity"></div>
            <div class="response-text"></div>
        </div>`;
    chatBox.appendChild(msg);
    scrollToBottom();

    // Store scoped references — no IDs, no global getElementById
    toolActivityEl = msg.querySelector('.tool-activity');
    responseTextEl = msg.querySelector('.response-text');
    stageLabelEl   = msg.querySelector('.agent-stage');

    return msg;
}

// ── Tool turn indicator ───────────────────────────────────────────────────

function appendToolTurn(tools) {
    if (!toolActivityEl) return;

    const item = document.createElement('div');
    item.className = 'tool-turn-item';
    const toolNames = tools.map(t => `<code>${escapeHtml(t)}</code>`).join(', ');
    item.innerHTML = `<span class="tool-turn-icon">
                          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                              <path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>
                          </svg>
                      </span>
                      <span class="tool-turn-label">Calling ${toolNames}</span>`;
    toolActivityEl.appendChild(item);
    scrollToBottom();
}

// ── Token streaming ───────────────────────────────────────────────────────

function appendToken(token) {
    if (!responseTextEl) return;
    tokenBuffer += token;
    responseTextEl.textContent = tokenBuffer;   // raw text while streaming
    // Clear the stage label once tokens start flowing
    if (stageLabelEl && stageLabelEl.textContent) stageLabelEl.textContent = '';
    scrollToBottom();
}

// ── Finalize ──────────────────────────────────────────────────────────────

function finalizeResponse(msgEl) {
    msgEl.classList.remove('streaming-bubble');

    // Add separator between tool turns and final answer if tools were used
    if (toolActivityEl && toolActivityEl.children.length > 0 && tokenBuffer) {
        const sep = document.createElement('div');
        sep.className = 'tool-separator';
        toolActivityEl.appendChild(sep);
    }

    // Render markdown into the response-text element
    if (responseTextEl) responseTextEl.innerHTML = marked.parse(tokenBuffer || '');

    scrollToBottom();
}

// ── Utilities ─────────────────────────────────────────────────────────────

function scrollToBottom() {
    const chatBox = document.getElementById('chat-box');
    chatBox.scrollTop = chatBox.scrollHeight;
}

function escapeHtml(text) {
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;');
}

// ── Send message ──────────────────────────────────────────────────────────

function sendMessage() {
    const input = document.getElementById('input');
    const text  = input.value.trim();
    if (!text) return;
    if (!sessionId) newSession();

    input.value = '';
    input.style.height = 'auto';
    setInputEnabled(false);
    setStatus('thinking');

    // Reset per-message state
    toolActivityEl = null;
    responseTextEl = null;
    stageLabelEl   = null;
    tokenBuffer    = '';

    appendUserMessage(text);
    const msgEl = createAssistantBubble();

    const params = encodeURIComponent(JSON.stringify({ session_id: sessionId, message: text }));
    const es = new EventSource('/api/agent/chat/stream?body=' + params);

    // Tool turn — agent decided to use tools
    es.addEventListener('turn', e => {
        try {
            const obj = JSON.parse(e.data);
            appendToolTurn(obj.tools || []);
            setStatus('thinking');
        } catch (_) {}
    });

    // Token — final answer streaming in
    es.addEventListener('token', e => {
        try {
            const obj = JSON.parse(e.data);
            if (typeof obj.token === 'string') {
                appendToken(obj.token);
                setStatus('streaming');
            }
        } catch (_) {}
    });

    // Done — stream complete
    es.addEventListener('done', () => {
        es.close();
        finalizeResponse(msgEl);
        setStatus('idle');
        setInputEnabled(true);
        document.getElementById('input').focus();
    });

    // Connection error
    es.addEventListener('error', () => {
        es.close();
        if (!tokenBuffer && responseTextEl) {
            responseTextEl.textContent = '[Connection error — please try again]';
        }
        msgEl.classList.remove('streaming-bubble');
        setStatus('idle');
        setInputEnabled(true);
    });
}

// ── Bootstrap ─────────────────────────────────────────────────────────────

document.addEventListener('DOMContentLoaded', () => {
    newSession();

    const input = document.getElementById('input');

    input.addEventListener('input', () => {
        input.style.height = 'auto';
        input.style.height = Math.min(input.scrollHeight, 200) + 'px';
    });

    input.addEventListener('keydown', e => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });

    document.getElementById('send').addEventListener('click', sendMessage);
    document.getElementById('new-session').addEventListener('click', newSession);
    document.getElementById('session-id').addEventListener('change', e => {
        sessionId = e.target.value.trim();
    });
});
