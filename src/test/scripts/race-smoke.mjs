/**
 * Headless two-bot race used to verify the server end to end without opening a browser.
 *
 * Usage:
 *   node scripts/race-smoke.mjs                     # talks to the backend directly
 *   BASE=http://localhost:5173 node scripts/race-smoke.mjs   # through the Vite dev proxy
 *
 * Add --cheat to submit inflated progress and prove the anti-cheat clamps it (flagged: true).
 *
 * Both bots register real accounts first. Since auth became mandatory there is no anonymous socket to
 * open, so the bots log in over HTTP and pass the access token on the WebSocket upgrade.
 *
 * The accounts are reused across runs on purpose: registration is rate limited per IP, so a fresh
 * pair every run would lock the script out after a few attempts. Reuse also keeps the leaderboard
 * readable. Set RUN_ID to isolate a run against new accounts.
 */
const base = process.env.BASE ?? 'http://localhost:8081';
const wsBase = `${base.replace(/^http/, 'ws')}/ws/game`;
const cheat = process.argv.includes('--cheat');
const runId = process.env.RUN_ID ?? 'smoke';
const PASSWORD = 'correct-horse-battery';

const WPM = { Alice: 78, Bob: 63 };

async function postJson(path, body) {
  const response = await fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`POST ${path} -> ${response.status} ${text}`);
  }
  return text ? JSON.parse(text) : null;
}

/**
 * Signs a bot in, registering first and falling back to login.
 *
 * <p>Registration is what proves the signup path works; login is the realistic path for a returning
 * player. Two failures are recoverable rather than fatal: `username_taken` means the account already
 * exists from an earlier run, and `rate_limited` means this machine has created enough accounts
 * recently to hit the per-IP registration cap — an existing account can still sign in.
 */
async function signIn(label) {
  const username = `${label.toLowerCase()}-${runId}`;
  try {
    const session = await postJson('/api/auth/register', { username, password: PASSWORD });
    console.log(`[${label}] registered ${username}`);
    return session;
  } catch (error) {
    const reason = String(error);
    if (!reason.includes('username_taken') && !reason.includes('rate_limited')) throw error;
    console.log(`[${label}] ${username} not registered (${reason.includes('rate_limited') ? 'registration rate limit' : 'already exists'}), signing in`);
    return postJson('/api/auth/login', { username, password: PASSWORD });
  }
}

function mkBot(label, token) {
  const bot = {
    name: label,
    ws: null,
    me: null,
    text: '',
    total: 0,
    startAt: 0,
    standings: null,
    phases: new Set(),
  };
  // The token rides the query string: a browser cannot set headers on a WebSocket upgrade, so the
  // server authorizes the handshake from here.
  bot.ws = new WebSocket(`${wsBase}?token=${encodeURIComponent(token)}`);
  bot.ws.onopen = () => console.log(`[${label}] socket open, waiting for welcome`);
  bot.ws.onmessage = (event) => {
    const { type, data } = JSON.parse(event.data);
    bot.phases.add(type);
    if (type === 'welcome') {
      // Only now is the bot actually a player, so only now does it queue. Sending earlier would race
      // the server creating the slot.
      bot.me = data;
      console.log(`[${label}] admitted as ${data.nickname}`);
      bot.ws.send(JSON.stringify({ type: 'join_quick' }));
    } else if (type === 'race_start') {
      bot.text = data.text;
      bot.total = data.totalChars;
      bot.startAt = data.startAtEpochMs;
      console.log(
        `[${label}] race ${data.raceId}: ${data.totalChars} chars, starts in ${data.startAtEpochMs - Date.now()}ms`,
      );
    } else if (type === 'player_finished') {
      console.log(`[${label}] sees ${data.nickname} finish P${data.place} @ ${data.wpm} wpm`);
    } else if (type === 'race_over') {
      bot.standings = data.standings;
      console.log(
        `[${label}] race_over:`,
        data.standings
          .map((s) => `${s.nickname} P${s.place || '-'} ${s.wpm}wpm${s.dnf ? ' DNF' : ''}`)
          .join(' | '),
      );
    } else if (type === 'error') {
      console.log(`[${label}] error ${data.code}: ${data.message}`);
    }
  };
  bot.ws.onerror = () => console.log(`[${label}] socket error`);
  bot.ws.onclose = (event) => {
    if (!bot.standings && !bot.phases.has('welcome')) {
      console.log(`[${label}] socket refused (${event.code}) — token rejected or account over the socket cap`);
    }
  };
  return bot;
}

/** Types the text at the bot's target WPM, sending progress on the same cadence as the browser client. */
function autoType(bot) {
  let correct = 0;
  let errors = 0;
  let keystrokes = 0;
  let submitted = false;
  const perSecond = (WPM[bot.name] * 5) / 60;
  const timer = setInterval(() => {
    const elapsed = (Date.now() - bot.startAt) / 1000;
    if (elapsed <= 0) return;
    const target = cheat
      ? bot.total // jump straight to the end and watch the server clamp it
      : Math.min(bot.total, Math.floor(perSecond * elapsed));
    const delta = target - correct;
    keystrokes += delta;
    if (delta > 0 && Math.random() < 0.07) errors += 1;
    correct = target;
    bot.ws.send(JSON.stringify({ type: 'progress', correctChars: correct, errors, keystrokes }));
    if (correct >= bot.total && !submitted) {
      submitted = true;
      clearInterval(timer);
      bot.ws.send(JSON.stringify({ type: 'finish', correctChars: correct, errors, keystrokes }));
      console.log(`[${bot.name}] submitted finish (${correct} chars, ${errors} errors)`);
    }
  }, 120);
  setTimeout(() => clearInterval(timer), 120_000);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const aliceSession = await signIn('Alice');
  const bobSession = await signIn('Bob');

  const alice = mkBot('Alice', aliceSession.accessToken);
  const bob = mkBot('Bob', bobSession.accessToken);
  await sleep(1500);
  autoType(alice);
  autoType(bob);

  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline && !(alice.standings && bob.standings)) {
    await sleep(500);
  }

  if (!alice.standings || !bob.standings) {
    console.error('FAIL: race did not finish within 90s');
    alice.ws.close();
    bob.ws.close();
    process.exit(1);
  }

  const bothSawIt = alice.phases.has('state') && alice.phases.has('player_finished');
  console.log('phases seen by Alice:', [...alice.phases].join(','));
  console.log('live sync received:', bothSawIt);

  await sleep(1500);
  const leaderboard = await (await fetch(`${base}/api/leaderboard?limit=5`)).json();
  console.log(
    'leaderboard:',
    leaderboard.map((r) => `#${r.rank} ${r.nickname} best=${r.bestWpm}`).join(' | '),
  );

  // Race history is no longer public, so read it as Alice with her own token. That doubles as a check
  // that the private route rejects an anonymous caller.
  const authed = { headers: { Authorization: `Bearer ${aliceSession.accessToken}` } };
  const anonymous = await fetch(`${base}/api/me/races?limit=2`);
  console.log(`anonymous /api/me/races -> ${anonymous.status} (expect 401)`);

  const history = await (await fetch(`${base}/api/me/races?limit=2`, authed)).json();
  const last = history[0];
  console.log(`Alice last race: ${last.raceId} place=${last.place} wpm=${last.wpm} flagged=${last.flagged}`);

  const expectFlagged = cheat;
  const ok = expectFlagged ? last.flagged === true : last.flagged === false;
  const privateOk = anonymous.status === 401;
  console.log(
    ok && privateOk ? 'PASS' : 'FAIL',
    `— anti-cheat ${cheat ? 'flagged an inflated run' : 'accepted an honest run'}, private history enforced: ${privateOk}`,
  );

  alice.ws.close();
  bob.ws.close();
  process.exit(ok && privateOk ? 0 : 1);
})().catch((error) => {
  console.error('FAIL:', error.message);
  process.exit(1);
});