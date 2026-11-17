// A pretend Jarvis "Edith feed" for trying Edith's work account on the emulator (C38), with no PC, Outlook or Jarvis.
// Speaks the contract in docs/design/jarvis-feed-spec.md over HTTPS with a throwaway self-signed certificate.
//
//   node scripts/fake-jarvis.mjs            # listens on 0.0.0.0:47821; the emulator reaches it at 10.0.2.2:47821
//
// It prints the certificate fingerprint (what Edith shows for the owner to compare) and the pairing code, which is
// always ABCDE-FGHJK. The mail, events and tasks are made-up samples. Only drafts are "written": into drafts.json here.
import { createHash, createHmac, randomBytes } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:https'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const PORT = Number(process.env.PORT ?? 47821)
const CODE = 'ABCDE-FGHJK'
const dir = join(tmpdir(), 'fake-jarvis')
mkdirSync(dir, { recursive: true })
const keyFile = join(dir, 'key.pem')
const certFile = join(dir, 'cert.pem')
if (!existsSync(certFile)) {
  execFileSync('openssl', ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes', '-keyout', keyFile, '-out', certFile, '-days', '30', '-subj', '/CN=fake-jarvis'], { stdio: 'ignore' })
}
const cert = readFileSync(certFile, 'utf8')
const der = Buffer.from(cert.replace(/-----[^-]+-----/g, '').replace(/\s+/g, ''), 'base64')
const fingerprint = createHash('sha256').update(der).digest('hex')
const tokens = new Set()
const drafts = []
const now = Date.now()
const hour = 3_600_000
const day = 24 * hour
const midnight = new Date(); midnight.setHours(0, 0, 0, 0)

const mail = [
  { id: 'work:1:101', account: 'work', from: { name: 'Dana Whitfield', address: 'dana@corp.example' }, to: ['me@corp.example'], cc: [], subject: 'Q4 budget review: numbers attached', date: now - 2 * hour, unread: true, flagged: true, answered: false, toMe: 'to', preview: 'Hi, the Q4 numbers are in. Can you check the travel line before Thursday?', score: 8 },
  { id: 'work:1:102', account: 'work', from: { name: 'IT Service Desk', address: 'it@corp.example' }, to: ['all@corp.example'], cc: [], subject: 'Password expiry reminder', date: now - 20 * hour, unread: false, flagged: false, answered: false, toMe: 'other', preview: 'Your password expires in 7 days.', score: 3 },
  { id: 'work:1:103', account: 'work', from: { name: 'Priya Natarajan', address: 'priya@corp.example' }, to: ['me@corp.example'], cc: ['dana@corp.example'], subject: 'Lunch Friday?', date: now - 30 * hour, unread: true, flagged: false, answered: false, toMe: 'to', preview: 'Are you free for lunch on Friday to talk about the roadmap?', score: 5 },
]
const bodies = {
  'work:1:101': 'Hi,\n\nThe Q4 numbers are attached. Please check the travel line before Thursday: it looks high to me.\n\nThanks,\nDana',
  'work:1:102': 'Your password expires in 7 days. Change it at the usual place.',
  'work:1:103': 'Are you free for lunch on Friday to talk about the roadmap?\n\nPriya',
}
const events = [
  { id: 'ev1', calendar: 'cal:1', title: 'Budget review', start: midnight.getTime() + 14 * hour, end: midnight.getTime() + 15 * hour, allDay: false, location: 'Room 4B', recurring: false, joinUrl: 'https://teams.example/join/abc', organizer: 'Dana Whitfield', myResponse: 'accepted', showAs: 'busy' },
  { id: 'ev2', calendar: 'cal:1', title: 'Company holiday', start: midnight.getTime() + 2 * day, end: midnight.getTime() + 3 * day, allDay: true, recurring: false, showAs: 'free' },
  { id: 'ev3', calendar: 'cal:1', title: 'Roadmap lunch', start: midnight.getTime() + 4 * day + 12 * hour, end: midnight.getTime() + 4 * day + 13 * hour, allDay: false, recurring: false, showAs: 'busy' },
]
const tasks = [
  { id: 't1', title: 'Submit expense report', due: new Date(now + 2 * day).toISOString().slice(0, 10), done: false, importance: 'high' },
  { id: 't2', title: 'Review Priya’s roadmap draft', notes: 'Focus on the Q1 section', done: false, importance: 'normal' },
  { id: 't3', title: 'Book travel for the conference', done: true, completed: now - day, importance: 'normal' },
]

const send = (res, status, body) => { res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(JSON.stringify(body)) }
const err = (res, status, code, message) => send(res, status, { error: { code, message } })

createServer({ key: readFileSync(keyFile), cert }, (req, res) => {
  const url = new URL(req.url, 'https://x')
  const path = url.pathname.replace(/\/+$/, '')
  let body = ''
  req.on('data', (c) => (body += c))
  req.on('end', () => {
    console.log(req.method, path, url.search)
    if (path === '/v1/hello') return send(res, 200, { app: 'jarvis-feed', version: 1 })
    if (path === '/v1/pair' && req.method === 'POST') {
      const want = createHmac('sha256', CODE.replace('-', '')).update(fingerprint).digest('hex')
      if (JSON.parse(body || '{}').proof !== want) return err(res, 403, 'PAIRING_REFUSED', 'That pairing code wasn’t accepted.')
      const token = randomBytes(32).toString('base64url')
      tokens.add(token)
      return send(res, 200, { token, deviceId: 'fake-device', pcName: 'FAKE-PC', version: 1 })
    }
    const auth = (req.headers.authorization ?? '').replace(/^Bearer /, '')
    if (!tokens.has(auth)) return err(res, 401, 'UNAUTHORIZED', 'This phone isn’t paired with Jarvis.')
    if (path === '/v1/shared') return send(res, 200, { pcName: 'FAKE-PC', mail: [{ id: 'work', label: 'Work', email: 'me@corp.example' }], calendars: [{ id: 'cal:1', label: 'Work calendar', color: null }], tasks: [{ id: 'outlook', label: 'Outlook tasks' }] })
    if (path === '/v1/mail/messages') {
      const text = (url.searchParams.get('text') ?? '').toLowerCase()
      const unread = url.searchParams.get('unreadOnly') === '1'
      const items = mail.filter((m) => (!unread || m.unread) && (!text || `${m.subject} ${m.from.name} ${m.preview}`.toLowerCase().includes(text)))
      return send(res, 200, { items, more: false })
    }
    if (path.startsWith('/v1/mail/messages/')) {
      const id = decodeURIComponent(path.slice('/v1/mail/messages/'.length))
      const message = mail.find((m) => m.id === id)
      return message ? send(res, 200, { message, text: bodies[id], truncated: false, attachments: [] }) : err(res, 404, 'NOT_SHARED', 'No such message.')
    }
    if (path === '/v1/mail/drafts' && req.method === 'POST') {
      drafts.push(JSON.parse(body))
      writeFileSync('drafts.json', JSON.stringify(drafts, null, 2))
      return send(res, 200, { saved: true, note: 'Draft saved in Outlook (pretend).' })
    }
    if (path === '/v1/calendar/events') {
      const from = Number(url.searchParams.get('from')); const to = Number(url.searchParams.get('to'))
      return send(res, 200, { items: events.filter((e) => e.end > from && e.start < to), more: false })
    }
    if (path === '/v1/tasks/lists') return send(res, 200, { items: [{ id: 'outlook:tasks', title: 'Tasks' }], more: false })
    if (path === '/v1/tasks') return send(res, 200, { items: tasks.filter((t) => url.searchParams.get('completed') === '1' || !t.done), more: false })
    return err(res, 404, 'NOT_SHARED', 'That isn’t shared with Edith.')
  })
}).listen(PORT, '0.0.0.0', () => {
  console.log(`Pretend Jarvis on https://0.0.0.0:${PORT}  (emulator: 10.0.2.2:${PORT})`)
  console.log(`Fingerprint: ${fingerprint.slice(0, 24).match(/.{4}/g).join(' ')}   Pairing code: ${CODE}`)
})
