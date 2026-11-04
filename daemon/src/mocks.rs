use std::collections::VecDeque;

use crate::interfaces::{
    apply_event, BackendError, DisconnectReason, GamepadBackend, InputEvent, InputSource, PlayerState,
    SlotId, Transport, TransportError, FRAME_SIZE,
};

pub fn synthetic_input_frame(session_id: u32, seq: u32, timestamp_us: u64) -> [u8; FRAME_SIZE] {
    let mut frame = [0u8; FRAME_SIZE];
    frame[0..4].copy_from_slice(b"MNDO");
    frame[4] = 1;
    frame[5] = 3;
    frame[8..12].copy_from_slice(&session_id.to_le_bytes());
    frame[12..16].copy_from_slice(&seq.to_le_bytes());
    frame[16..24].copy_from_slice(&timestamp_us.to_le_bytes());
    frame
}

pub struct MockTransport {
    frames: VecDeque<[u8; FRAME_SIZE]>,
    writes: Vec<[u8; FRAME_SIZE]>,
    opened: bool,
    disconnected: Option<DisconnectReason>,
    clock_us: u64,
    interval_us: u64,
    next_at_us: u64,
    synthetic_seq: u32,
    session_id: u32,
}

impl MockTransport {
    pub fn new() -> Self {
        Self {
            frames: VecDeque::new(),
            writes: Vec::new(),
            opened: false,
            disconnected: None,
            clock_us: 0,
            interval_us: 1_000,
            next_at_us: 0,
            synthetic_seq: 1,
            session_id: 1,
        }
    }

    pub fn with_frames(frames: Vec<[u8; FRAME_SIZE]>) -> Self {
        let mut transport = Self::new();
        transport.frames = frames.into();
        transport
    }

    pub fn push_frame(&mut self, frame: [u8; FRAME_SIZE]) {
        self.frames.push_back(frame);
    }

    pub fn writes(&self) -> &[[u8; FRAME_SIZE]] {
        &self.writes
    }

    pub fn clock_us(&self) -> u64 {
        self.clock_us
    }

    pub fn disconnect(&mut self, reason: DisconnectReason) {
        self.disconnected = Some(reason);
    }

    pub fn advance(&mut self, delta_us: u64) {
        self.clock_us = self.clock_us.saturating_add(delta_us);
        while self.next_at_us < self.clock_us {
            let frame = synthetic_input_frame(self.session_id, self.synthetic_seq, self.next_at_us);
            self.frames.push_back(frame);
            self.synthetic_seq = self.synthetic_seq.wrapping_add(1);
            self.next_at_us = self.next_at_us.saturating_add(self.interval_us);
        }
    }
}

impl Default for MockTransport {
    fn default() -> Self {
        Self::new()
    }
}

impl Transport for MockTransport {
    fn open(&mut self) -> Result<(), TransportError> {
        self.opened = true;
        Ok(())
    }

    fn close(&mut self) -> Result<(), TransportError> {
        self.opened = false;
        Ok(())
    }

    fn read(&mut self, out: &mut [u8; FRAME_SIZE]) -> Result<bool, TransportError> {
        if !self.opened {
            return Err(TransportError::NotOpen);
        }
        if let Some(reason) = self.disconnected {
            return Err(TransportError::Disconnected { reason });
        }
        match self.frames.pop_front() {
            Some(frame) => {
                *out = frame;
                Ok(true)
            }
            None => Ok(false),
        }
    }

    fn write(&mut self, frame: &[u8; FRAME_SIZE]) -> Result<(), TransportError> {
        if !self.opened {
            return Err(TransportError::NotOpen);
        }
        self.writes.push(*frame);
        Ok(())
    }

    fn on_disconnect(&mut self) -> Option<DisconnectReason> {
        self.disconnected.take()
    }
}

pub struct MockGamepad {
    print_updates: bool,
    created: Vec<SlotId>,
    updates: Vec<(SlotId, PlayerState)>,
    resets: Vec<SlotId>,
    destroyed: Vec<SlotId>,
}

impl MockGamepad {
    pub fn recording() -> Self {
        Self {
            print_updates: false,
            created: Vec::new(),
            updates: Vec::new(),
            resets: Vec::new(),
            destroyed: Vec::new(),
        }
    }

    pub fn printing() -> Self {
        let mut gamepad = Self::recording();
        gamepad.print_updates = true;
        gamepad
    }

    pub fn created(&self) -> &[SlotId] {
        &self.created
    }

    pub fn updates(&self) -> &[(SlotId, PlayerState)] {
        &self.updates
    }

    pub fn resets(&self) -> &[SlotId] {
        &self.resets
    }

    fn is_alive(&self, slot: SlotId) -> bool {
        self.created.contains(&slot) && !self.destroyed.contains(&slot)
    }
}

impl GamepadBackend for MockGamepad {
    fn create_slot(&mut self, slot: SlotId) -> Result<(), BackendError> {
        if !self.created.contains(&slot) {
            self.created.push(slot);
        }
        self.destroyed.retain(|item| *item != slot);
        Ok(())
    }

    fn update(&mut self, slot: SlotId, state: &PlayerState) -> Result<(), BackendError> {
        if !self.is_alive(slot) {
            return Err(BackendError::SlotNotFound(slot));
        }
        self.updates.push((slot, *state));
        if self.print_updates {
            println!("slot {} {:?}", slot.get(), state);
        }
        Ok(())
    }

    fn reset(&mut self, slot: SlotId) -> Result<(), BackendError> {
        if !self.is_alive(slot) {
            return Err(BackendError::SlotNotFound(slot));
        }
        self.resets.push(slot);
        let neutral = PlayerState::neutral();
        self.updates.push((slot, neutral));
        if self.print_updates {
            println!("slot {} reset -> {:?}", slot.get(), neutral);
        }
        Ok(())
    }

    fn destroy(&mut self, slot: SlotId) -> Result<(), BackendError> {
        if !self.is_alive(slot) {
            return Err(BackendError::SlotNotFound(slot));
        }
        if !self.destroyed.contains(&slot) {
            self.destroyed.push(slot);
        }
        Ok(())
    }

    fn slots(&self) -> Vec<SlotId> {
        let mut slots = self.created.clone();
        slots.retain(|slot| !self.destroyed.contains(slot));
        slots.sort();
        slots
    }
}

pub struct MockInputSource {
    events: VecDeque<(u64, InputEvent)>,
}

impl MockInputSource {
    pub fn new(events: Vec<(u64, InputEvent)>) -> Self {
        Self {
            events: events.into(),
        }
    }

    pub fn push(&mut self, at_us: u64, event: InputEvent) {
        self.events.push_back((at_us, event));
    }

    pub fn apply_all(&mut self, now_us: u64, state: &mut PlayerState) -> usize {
        let mut applied = 0;
        while let Some(event) = self.poll(now_us) {
            apply_event(state, event);
            applied += 1;
        }
        applied
    }
}

impl InputSource for MockInputSource {
    fn poll(&mut self, now_us: u64) -> Option<InputEvent> {
        match self.events.front() {
            Some((at_us, _)) if *at_us <= now_us => self.events.pop_front().map(|(_, event)| event),
            _ => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::interfaces::ButtonId;

    #[test]
    fn mock_transport_generates_1000_frames_per_second() {
        let mut transport = MockTransport::new();
        transport.open().unwrap();
        transport.advance(1_000_000);
        let mut out = [0u8; FRAME_SIZE];
        let mut count = 0;
        while transport.read(&mut out).unwrap() {
            count += 1;
        }
        assert_eq!(count, 1000);
    }

    #[test]
    fn mock_input_source_is_scripted_and_ordered() {
        let mut source = MockInputSource::new(vec![
            (10, InputEvent::Button { id: ButtonId::A, pressed: true }),
            (20, InputEvent::Button { id: ButtonId::A, pressed: false }),
        ]);
        let mut state = PlayerState::neutral();
        assert_eq!(source.apply_all(5, &mut state), 0);
        assert_eq!(source.apply_all(10, &mut state), 1);
        assert_eq!(state.buttons & ButtonId::A.mask(), 1);
        assert_eq!(source.apply_all(20, &mut state), 1);
        assert_eq!(state.buttons, 0);
    }

    #[test]
    fn disconnect_notification_is_delivered_once() {
        let mut transport = MockTransport::new();
        transport.open().unwrap();
        transport.disconnect(DisconnectReason::CableRemoved);
        let mut out = [0u8; FRAME_SIZE];
        assert!(transport.read(&mut out).is_err());
        assert_eq!(transport.on_disconnect(), Some(DisconnectReason::CableRemoved));
        assert_eq!(transport.on_disconnect(), None);
    }
}
