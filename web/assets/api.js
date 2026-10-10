// Warstwa danych: Supabase (PostgREST + Auth). Tabele i formaty: docs/CLOUD_API.md, schemat: backend/supabase/schema.sql.
// Strona używa publicznego klucza anon/publishable – dostępu pilnują polityki RLS w bazie.

function must({ data, error }) {
  if (error) throw new Error(error.message || 'Błąd serwera');
  return data;
}

export function createApi(cfg) {
  const db = window.supabase.createClient(cfg.url, cfg.key, { auth: { persistSession: true, autoRefreshToken: true } });
  const count = async (table, filter) => {
    let q = db.from(table).select('*', { count: 'exact', head: true });
    if (filter) q = filter(q);
    const { count: c, error } = await q;
    if (error) throw new Error(error.message);
    return c || 0;
  };
  const flatten = (rows) => rows.map((e) => ({ ...e, segment: e.segments || null }));

  return {
    demo: false,
    async getUser() {
      const { data } = await db.auth.getSession();
      const u = data?.session?.user;
      return u ? { id: u.id, email: u.email || '' } : null;
    },
    onAuth(cb) {
      db.auth.onAuthStateChange((_e, session) => {
        const u = session?.user;
        cb(u ? { id: u.id, email: u.email || '' } : null);
      });
    },
    async signIn(email, password) { must(await db.auth.signInWithPassword({ email, password })); },
    async signUp(email, password) { must(await db.auth.signUp({ email, password })); },
    async signOut() { await db.auth.signOut(); },

    async counts() {
      const [segments, efforts, rides] = await Promise.all([
        count('segments'), count('segment_efforts'), count('rides', (q) => q.eq('visibility', 'public')),
      ]);
      return { segments, efforts, rides };
    },
    async latestEfforts(limit = 12) {
      return flatten(must(await db.from('segment_efforts')
        .select('id,segment_uid,owner,athlete,started_at,time_sec,created_at,segments(uid,name,sport,length_m)')
        .order('created_at', { ascending: false }).limit(limit)));
    },
    /** lista odcinków z liczbą wyników i najlepszym czasem (+ geometria do podglądu) */
    async segmentList({ q = '', sport = null, limit = 24 } = {}) {
      let query = db.from('segment_overview').select('uid,name,sport,length_m,author,efforts,best_sec,created_at')
        .order('efforts', { ascending: false }).order('created_at', { ascending: false }).limit(limit);
      if (q) query = query.ilike('name', `%${q.replace(/[%_]/g, ' ')}%`);
      if (sport != null) query = query.eq('sport', sport);
      const rows = must(await query);
      if (!rows.length) return rows;
      const geoms = must(await db.from('segments').select('uid,geom').in('uid', rows.map((r) => r.uid)));
      const byUid = new Map(geoms.map((g) => [g.uid, g.geom]));
      return rows.map((r) => ({ ...r, geom: byUid.get(r.uid) || '' }));
    },
    async getSegment(uid) {
      const { data, error } = await db.from('segments').select('uid,name,sport,length_m,geom,author,created_at').eq('uid', uid).maybeSingle();
      if (error) throw new Error(error.message);
      return data;
    },
    async getEfforts(uid) {
      return must(await db.from('segment_efforts').select('id,owner,athlete,started_at,time_sec,profile,created_at')
        .eq('segment_uid', uid).order('time_sec', { ascending: true }).limit(100));
    },
    async publicRides(limit = 30) {
      return must(await db.from('rides').select('owner,started_at,sport,name,athlete,distance_m,moving_s,ascent_m,kcal,avg_hr,tss,terrain_enc,polyline,visibility,created_at')
        .eq('visibility', 'public').order('started_at', { ascending: false }).limit(limit));
    },
    async myRides() {
      const u = await this.getUser();
      if (!u) return [];
      return must(await db.from('rides').select('owner,started_at,sport,name,athlete,distance_m,moving_s,ascent_m,kcal,avg_hr,tss,terrain_enc,polyline,visibility,created_at')
        .eq('owner', u.id).order('started_at', { ascending: false }).limit(100));
    },
    async getRide(owner, startedAt) {
      const { data, error } = await db.from('rides').select('*').eq('owner', owner).eq('started_at', startedAt).maybeSingle();
      if (error) throw new Error(error.message);
      return data;
    },
    async myBests() {
      const u = await this.getUser();
      if (!u) return [];
      return flatten(must(await db.from('segment_efforts')
        .select('id,segment_uid,owner,athlete,started_at,time_sec,created_at,segments(uid,name,sport,length_m)')
        .eq('owner', u.id).order('created_at', { ascending: false }).limit(100)));
    },
    async myRoutes() {
      const u = await this.getUser();
      if (!u) return [];
      return must(await db.from('routes').select('id,owner,name,sport,distance_m,ascent_m,geom,is_public,source,created_at')
        .eq('owner', u.id).order('created_at', { ascending: false }).limit(50));
    },
    async saveRoute(route) {
      const u = await this.getUser();
      if (!u) throw new Error('Zaloguj się, aby zapisać trasę');
      const row = { owner: u.id, client_uid: 'w' + Date.now(), source: 'web', author: (u.email || '').split('@')[0].slice(0, 40), ...route };
      return must(await db.from('routes').insert(row).select().single());
    },
    async deleteRoute(id) { must(await db.from('routes').delete().eq('id', id)); },
  };
}
