# Moozaq
**Product Brief | Version 0.1 | October 2026**

> Working baseline, saved October 16, 2026. This brief is a starting point, not a fixed specification. Product scope, priorities, architecture, and other decisions may change as we learn. Later explicit decisions take precedence; see [Current direction](current-direction.md).

*Your music. Your mood. Everywhere.*

## 1. Product Overview

**Moozaq** is a music discovery and playback application focused on long-form, themed music mixes, typically lasting between one and four hours.

Rather than competing with Spotify or YouTube as a general-purpose music platform, Moozaq focuses on a specific listening experience: discovering atmospheric, genre-specific music and playing it effortlessly through home audio systems.

Users browse a curated collection of music mixes organized by mood, genre, era, and atmosphere, then play them on their preferred device or connected speakers.

The initial catalog draws inspiration from long-form YouTube mixes, including noir jazz, psychedelic desert rock, Persian jazz, Afro house, ambient electronic music, and vintage lounge.

**Core proposition:** Turn the fragmented world of long-form music mixes into a beautifully organized, speaker-first listening experience.

## 2. Problem

YouTube has become an important destination for discovering long-form thematic music. Thousands of videos feature extended mixes combined with distinctive visual identities.

However, YouTube is not optimized for this particular listening experience.

| Problem | User impact |
|---|---|
| Limited audio-only casting from the standard YouTube Android app | Cannot easily play YouTube mixes on Google Home/Nest speakers |
| Video-centric interface | Unnecessary emphasis on watching rather than listening |
| Poor organization of niche mixes | Difficult to build a personal thematic collection |
| Discovery driven by YouTube recommendations | Limited control over genre and mood exploration |
| Long mixes scattered across channels | No consistent library or listening experience |
| Inconsistent speaker support | Difficult to move playback between audio devices |

Moozaq addresses these problems through focused discovery, curation, and connected audio playback.

## 3. Target Audience

**Primary audience**

People who enjoy atmospheric music during everyday activities:

- Working, studying, or reading
- Relaxing at home
- Cooking or entertaining
- Creating an atmosphere in a room
- Exploring obscure or niche musical genres

**Early adopters**

Technically comfortable listeners who already use YouTube for extended music mixes and own Google Cast-enabled speakers.

**Secondary audience**

Listeners with multiroom audio systems who want a simpler alternative to managing conventional playlists.

## 4. Product Experience

### Discovery

Users open Moozaq and browse music through visual collections.

Example themes:

| Theme | Musical character |
|---|---|
| Noir Jazz | Dark, smoky, late-night jazz |
| Desert Psychedelia | Atmospheric psychedelic rock |
| Persian Vintage | Iranian-inspired vintage jazz and funk |
| Afro House | Rhythmic electronic and African influences |
| Cozy Jazz | Warm, mellow background jazz |
| Deep Ambient | Minimal, cinematic soundscapes |
| Retro Electronica | Analog synthesizers and electronic nostalgia |

Each mix features cover artwork, title, duration, source attribution, and thematic tags.

### Playback

The ideal playback flow is:

1. Open Moozaq on mobile or desktop.
2. Choose a theme or mix.
3. Select an available playback device.
4. Start playback.
5. Continue listening independently of the controlling device, wherever the playback source and receiver support this.

The experience should be as simple as turning on a radio.

### Personal library

Users can:

- Save favorite mixes.
- Create their own collections.
- Bookmark mixes from YouTube.
- Resume previously played content when supported.
- Build queues of long-form mixes.

### Connected audio

Google Cast is the first target platform, supporting compatible Google Home/Nest speakers and speaker groups.

Future integrations may include Amazon Alexa/Echo, Sonos, AirPlay, and Bluetooth playback.

## 5. Content Strategy

Moozaq is initially a **curation platform, not a music-generation service**.

The product catalog consists of references to existing mixes and, where permitted, directly playable audio content.

### YouTube-based discovery

Users and curators can save YouTube URLs, retrieve available metadata through authorized APIs, categorize mixes, and organize collections.

Official YouTube playback must follow YouTube's player and API policies. YouTube URLs alone do not provide an authorized audio stream for arbitrary Google Cast receivers.

### Direct audio playback

Moozaq can stream audio to compatible speakers when it has access to an authorized, compatible audio source.

Potential sources include:

- Licensed music mixes
- Direct creator partnerships
- Rights-cleared audio published by independent artists
- User-owned audio files for supported personal-use functionality

### Critical constraint

Although unofficial software can technically extract audio from YouTube and relay it to Google Cast receivers, YouTube's terms and API policies restrict unauthorized downloading, audio separation, and alternative playback.

A public commercial product must not assume that YouTube audio extraction is an acceptable production architecture.

The initial technical prototype may investigate feasibility, but production playback requires an authorized content strategy.

## 6. MVP Scope

### P0: Essential features

| Feature | Description |
|---|---|
| Visual music catalog | Curated grid of thematic mixes |
| Themes and genres | Browse by mood, style, and atmosphere |
| Mix detail | Artwork, description, duration, source |
| YouTube link import | Add references to existing mixes |
| Favorites | Save preferred mixes |
| Native browser playback | Play supported content through official players |
| Google Cast playback | Cast authorized audio streams to supported speakers |
| Basic transport controls | Play, pause, skip, seek, volume where supported |
| Responsive interface | Android-oriented mobile web and desktop |
| Admin catalog management | Add, edit, tag, and organize mixes |

### P1: Next iteration

- User accounts and persistent libraries
- User-created collections
- Continuous playback queues
- Speaker group selection
- Recently played history
- Search and filtering
- Automatic metadata enrichment
- Creator submissions

### P2: Future capabilities

- Amazon Echo and Sonos support
- Cross-device session synchronization
- Intelligent mood-based recommendations
- AI-assisted tagging and categorization
- Personalized continuous radio stations
- Native Android and iOS apps
- Creator channels and curated editorial collections

## 7. Proposed Technical Architecture

### Frontend

A responsive web application or Progressive Web App (PWA), optimized for mobile-first use.

Suggested technologies:

- React / Next.js, or Vue
- TypeScript
- Responsive component system
- Google Cast Web Sender SDK where supported
- Official YouTube embedded player

Native Android integration may be required to deliver the preferred Cast experience reliably across mobile environments. The web-only approach should be tested before committing to a PWA-only strategy.

### Backend

A lightweight API serving the catalog and user data.

Given an existing preference for practical, low-maintenance infrastructure, Laravel would also be a suitable option.

Core responsibilities:

- Mix metadata and classification
- User profiles and favorites
- Collections and playlists
- Content-source management
- Playback eligibility
- Admin moderation

### Database

PostgreSQL.

Illustrative entities:

| Entity | Purpose |
|---|---|
| `mixes` | Title, description, artwork, duration |
| `sources` | YouTube references or authorized audio URLs |
| `themes` | Music themes and moods |
| `tags` | Flexible categorization |
| `users` | Accounts and preferences |
| `favorites` | User-saved mixes |
| `collections` | Curated and personal playlists |
| `collection_items` | Playlist ordering |
| `playback_sessions` | Active sessions and playback state |

Each source should have an explicit playback capability and rights status.

### Media delivery

For authorized audio:

1. Client requests playback.
2. Backend provides a permitted media URL.
3. Google Cast sender starts a receiver session.
4. Cast receiver streams audio directly from the media endpoint.
5. Client controls the playback session.

Audio delivery should use HTTPS, appropriate media formats, and byte-range support where needed.

### Infrastructure

Initial deployment can remain inexpensive:

- Static or edge-hosted frontend
- Small Laravel or Node.js backend
- Managed PostgreSQL
- Object storage for artwork
- CDN or media storage for licensed audio
- No audio processing pipeline initially

Audio hosting and bandwidth will become important costs once the service streams its own media.

## 8. Technical Proof of Concept

Before building the full application, validate the most important assumption:

**Can Moozaq deliver a smooth, reliable long-form listening experience through Google Home/Nest speakers?**

The proof of concept should demonstrate:

| Test | Acceptance criterion |
|---|---|
| Device discovery | Identify available compatible Cast devices |
| Direct audio casting | Start playback of an authorized MP3/AAC stream |
| Background operation | Playback continues when the phone is locked |
| Playback control | Pause, resume, seek, and volume function correctly |
| Long-form playback | A three-hour stream plays without unwanted interruption |
| Speaker groups | Playback works on a compatible multiroom group |
| Source behavior | YouTube references open through an authorized playback route |
| Network recovery | Playback and controls recover appropriately from interruptions |

Test separately on Android, desktop Chrome, and the intended PWA installation environment.

**Go/no-go decision:** Do not commit to a production playback architecture until direct audio casting and the supported content-source model are both demonstrated.

## 9. Business Model

The initial product should focus on adoption and a compelling listening experience rather than immediate monetization.

Potential models:

**Freemium:** Free discovery and basic playback, with paid personalization, advanced collections, or additional integrations.

**Creator partnerships:** Independent music creators supply authorized mixes and receive discovery, attribution, and potential revenue sharing.

**Premium radio:** Subscriber access to exclusive, licensed long-form mixes and curated channels.

Any commercial model needs to account for music rights, infrastructure costs, and platform requirements.

## 10. Differentiation

Moozaq does not attempt to replicate Spotify's vast catalog or YouTube's video recommendation engine.

Its differentiation is a narrow but valuable listening experience:

- **Atmosphere-first:** Discover music through moods and aesthetics, not just artists.
- **Long-form by design:** Extended listening sessions, not individual songs.
- **Speaker-first:** Optimized for listening throughout the home.
- **Human curation:** Distinctive collections rather than generic algorithmic playlists.
- **Minimal interaction:** Select a mood, press play, and walk away.

## 11. Risks and Open Questions

| Risk | Mitigation |
|---|---|
| YouTube playback restrictions | Separate YouTube discovery from authorized direct audio playback |
| Music copyright and licensing | Work with rights holders and appropriately licensed content |
| Google Cast compatibility | Validate receivers, speaker groups, and mobile environments early |
| Long-form streaming expenses | Estimate bandwidth and hosting costs before scaling |
| Limited initial catalog | Curate a focused set of excellent mixes rather than thousands of mediocre ones |
| Trademark conflict | Conduct clearance before adopting Moozaq commercially |

**Brand warning:** Moozaq is a working name. Its phonetic similarity to the existing Muzak music brand presents a potential trademark risk. Domain, app-store, and trademark availability are not established.

## 12. Success Metrics

Early product performance should be measured through listening behavior, not only registrations.

| Metric | What it tells us |
|---|---|
| First-play conversion | Can users discover and start a mix? |
| Cast success rate | Does speaker playback work reliably? |
| Weekly active listeners | Is the service becoming a habit? |
| Listening hours per user | Are listeners enjoying extended sessions? |
| Repeat listening rate | Do users return to the same mixes? |
| Favorites per user | Is the catalog worth collecting? |
| Playback failure rate | Is the experience technically dependable? |

## 13. Product Roadmap

**Phase 1: Technical validation**

Build a minimal player and prove Google Cast playback with authorized audio. Investigate YouTube discovery and official playback limitations.

**Phase 2: Curated MVP**

Build the catalog, themed collections, basic favorites, responsive interface, and speaker selection.

**Phase 3: Private beta**

Recruit listeners with Google Home/Nest systems. Evaluate actual household listening behavior, catalog appeal, playback reliability, and retention.

**Phase 4: Public launch**

Release with a legally supported content and streaming model, a cleared brand identity, and a sustainable infrastructure setup.

## 14. Product Vision

Moozaq aims to become the simplest way to fill a room with interesting music.

Not another streaming service built around choosing individual songs. Not another video platform demanding visual attention.

A modern, curated radio experience built around atmosphere, discovery, and effortless listening.

**Find your mood. Press play. Let it run.**
