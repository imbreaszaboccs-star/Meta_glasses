'use strict';
/* WebMCP: FocusLens tools for Meta AI on the glasses ("Hey Meta, start my focus session").
 * Meta exposes document.modelContext to the web app in front of the user (Developer Mode or rollout; 10 s per call).
 * Every tool is a relay command the phone already handles, so this file only maps voice -> command -> ack.
 * Runs after app.js and uses its globals (api, pending, running, paired, phoneOnline, MOCK).
 * Dev: with ?mock=1 and no document.modelContext, tools land in window.focuslensTools for console testing:
 *   focuslensTools.add_note.execute({ text: 'Buy milk' })
 */
(function () {
  const ACK_MS = 8000; // inside Meta's 10 s budget

  // POST the command, then wait until applyState() consumes its ack (it deletes the pending entry and banners the result).
  async function command(type, args, label) {
    if (!paired()) throw new Error('FocusLens is not paired with the phone yet.');
    if (!MOCK && !phoneOnline) throw new Error('The FocusLens phone app is offline.');
    const r = await api('/api/r/cmd', { method: 'POST', body: { type, args: args || {} } });
    if (r.status === 409) throw new Error('The FocusLens phone app is offline.');
    if (r.status !== 202 || !r.data || r.data.id == null) throw new Error('FocusLens refused the request (' + r.status + ').');
    const id = r.data.id;
    let ack = null;
    pending.set(id, { label, onAck: (a) => { ack = a; } });
    const until = Date.now() + ACK_MS;
    while (!ack && Date.now() < until) { await new Promise((ok) => setTimeout(ok, 600)); await fetchState(); render(); }
    if (!ack) return 'Sent to the phone; it has not confirmed yet.';
    if (!ack.ok) throw new Error(String(ack.error || 'The phone could not do that.'));
    return 'Done.';
  }

  const TOOLS = [
    { name: 'focuslens_start_session',
      description: 'Start a FocusLens focus session (lecture, homework or desk work is detected automatically). Use when the user wants to start focusing, studying or a session.',
      execute: async () => (running() ? 'A session is already running.' : command('start_focus', {}, null)) },
    { name: 'focuslens_end_session',
      description: 'End the running FocusLens focus session.',
      execute: async () => (running() ? command('stop_focus', {}, null) : 'No session is running.') },
    { name: 'focuslens_get_help',
      description: 'The user is stuck or zoned out: FocusLens looks at what they see and the last minutes of audio and shows the next small step on the display.',
      execute: async () => {
        if (!running()) throw new Error('Start a FocusLens session first.');
        await command('help', {}, null);
        return 'Looking now; the next step will appear on the display.';
      } },
    { name: 'focuslens_save_for_later',
      description: 'Bookmark this moment (photo + last 60 seconds of audio) as a FocusLens card to review in the evening.',
      execute: async () => {
        if (!running()) throw new Error('Start a FocusLens session first.');
        return command('bookmark', {}, null);
      } },
    { name: 'focuslens_add_note',
      description: 'Save a short note or to-do to the FocusLens notes list, e.g. "remember to email Anna".',
      inputSchema: { type: 'object', properties: { text: { type: 'string', description: 'The note, in the words the user said (max 300 characters).' } }, required: ['text'] },
      execute: async (input) => {
        const text = String((input && input.text) || '').trim().slice(0, 300);
        if (!text) throw new Error('The note is empty.');
        return command('add_note', { text }, 'Saved to notes');
      } },
  ];

  const mc = document.modelContext;
  if (mc && typeof mc.registerTool === 'function') {
    for (const t of TOOLS) Promise.resolve(mc.registerTool(t)).catch(() => { /* already registered (InvalidStateError) */ });
  } else if (MOCK) {
    window.focuslensTools = Object.fromEntries(TOOLS.map((t) => [t.name.replace('focuslens_', ''), t]));
  }
})();
