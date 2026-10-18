/** GitHub release URLs go here later. */
export const downloads = {
  android: "#",
  mac: "#",
};

export const site = {
  url: "https://moozaq.com",
  title: "Moozaq — Set the mood. Let it play.",
  description:
    "Free and open source. No ads, no accounts, and no subscriptions. Long, unhurried vibes for whatever you're getting into, played on your home speakers from Android or Mac.",
  image: "/og.png",
};

/**
 * One long recording from a YouTube creator. The page calls these "vibes", not
 * mixes, and uses them only as artwork. Do not show counts or durations: the
 * catalog is large and changes.
 */
export type Vibe = {
  id: string;
  label: string;
  creator: string;
};

export type Mood = {
  id: string;
  name: string;
  /** Short label for the tuner's preset buttons and dial. */
  preset: string;
  line: string;
  vibes: Vibe[];
};

const vibe = (id: string, label: string, creator: string): Vibe => ({ id, label, creator });

export const art = (id: string) => `/artwork/${id}.jpg`;

export const moods: Mood[] = [
  {
    id: "focus",
    name: "Find your focus",
    preset: "Focus",
    line: "Music for getting into the zone.",
    vibes: [
      vibe("dKUL1WnIG-Y", "Rendezvous at Lunar Base 7", "The Safehouse Lounge"),
      vibe("4ZGkurkaePg", "The Prague Cipher", "The Safehouse Lounge"),
      vibe("s18iBl69S1o", "The Palm Springs Sound", "Chill Palace"),
    ],
  },
  {
    id: "morning",
    name: "Ease into the day",
    preset: "Morning",
    line: "Warm sounds. No rush.",
    vibes: [
      vibe("PVXQeJM-bNQ", "Just Being Myself.", "Sepia Lounge"),
      vibe("45mMlpIu5GM", "Coffee First. Always.", "Sepia Lounge"),
      vibe("7WWEJWVlVps", "No Plans. Just Coffee.", "Sepia Lounge"),
    ],
  },
  {
    id: "cooking",
    name: "Something's cooking",
    preset: "Cooking",
    line: "Give the kitchen its own rhythm.",
    vibes: [
      vibe("itSHCwtXico", "Havana Noir", "Afterhours Club '70"),
      vibe("vcAflQW9v7w", "Dark Naples", "Afterhours Club '70"),
      vibe("BLEdysTwz3E", "The Amalfi Coast Sound", "Chill Palace"),
    ],
  },
  {
    id: "guests",
    name: "Have people over",
    preset: "Company",
    line: "A room with company, and a sound to match.",
    vibes: [
      vibe("Fkq2A_NvINs", "Distant Signal", "Noir Tiki Lounge"),
      vibe("nDduT_wC08o", "The Last Run to Elko", "Noir Tiki Lounge"),
      vibe("i30BcD0yxOQ", "Neon Mirage at the Sands", "The Safehouse Lounge"),
    ],
  },
  {
    id: "late",
    name: "Stay up a little longer",
    preset: "Late",
    line: "For the hour when the day is over but you are not.",
    vibes: [
      vibe("uU7xhv-bdl8", "Red Halo Motel", "Jazz Haze"),
      vibe("nO1BgJFMT2o", "Golden Vice Club", "Jazz Haze"),
      vibe("Xdho5SVxEVI", "Midnight Fever Motel", "Jazz Haze"),
    ],
  },
  {
    id: "drift",
    name: "Drift somewhere else",
    preset: "Drift",
    line: "For when you want the room to take you somewhere.",
    vibes: [
      vibe("r6gcU96S-dc", "Altered Streets", "Futuristic Sound"),
      vibe("ZwJXMB5Kkn4", "Harlequin Dreams", "Futuristic Sound"),
      vibe("STWE18yL5PU", "Abandoned Spaceship Dub", "Futuristic Sound"),
    ],
  },
];

const byId = (id: string) => moods.flatMap((m) => m.vibes).find((v) => v.id === id)!;

/** Artwork used in the hero, the phone mockups and the Mac mockup. */
export const featured = {
  heroLeft: byId("Xdho5SVxEVI"),
  heroRight: byId("Fkq2A_NvINs"),
  nowPlaying: byId("PVXQeJM-bNQ"),
  browse: [byId("7WWEJWVlVps"), byId("45mMlpIu5GM")],
  macRows: [
    { mood: "Ease into the day", vibes: ["PVXQeJM-bNQ", "7WWEJWVlVps", "45mMlpIu5GM", "s18iBl69S1o"].map(byId) },
    { mood: "Stay up a little longer", vibes: ["uU7xhv-bdl8", "nO1BgJFMT2o", "i30BcD0yxOQ", "Xdho5SVxEVI"].map(byId) },
  ],
};
