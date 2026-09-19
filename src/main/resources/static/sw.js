/* Temporary API-origin worker retirement. Retain through the documented rollout cutoff. */
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", event => {
  event.waitUntil((async () => {
    const keys = await caches.keys();
    await Promise.all(keys.filter(key => key.startsWith("workbox-") || key.startsWith("yarumo-")).map(key => caches.delete(key)));
    const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    await Promise.all(clients.map(async client => {
      if (!client.url.includes("__yarumo_sw_retired=1")) {
        await client.navigate(client.url + (client.url.includes("?") ? "&" : "?") + "__yarumo_sw_retired=1");
      }
    }));
    await self.registration.unregister();
  })());
});
