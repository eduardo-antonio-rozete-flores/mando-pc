use mando_daemon::interfaces::{
    BackendError, DisconnectReason, GamepadBackend, PlayerState, SlotId, Transport, TransportError,
    FRAME_SIZE,
};
use mando_daemon::mocks::{synthetic_input_frame, MockGamepad, MockTransport};

struct AlternateTransport(MockTransport);

impl Transport for AlternateTransport {
    fn open(&mut self) -> Result<(), TransportError> {
        self.0.open()
    }

    fn close(&mut self) -> Result<(), TransportError> {
        self.0.close()
    }

    fn read(&mut self, out: &mut [u8; FRAME_SIZE]) -> Result<bool, TransportError> {
        self.0.read(out)
    }

    fn write(&mut self, frame: &[u8; FRAME_SIZE]) -> Result<(), TransportError> {
        self.0.write(frame)
    }

    fn on_disconnect(&mut self) -> Option<DisconnectReason> {
        self.0.on_disconnect()
    }
}

struct AlternateGamepad(MockGamepad);

impl GamepadBackend for AlternateGamepad {
    fn create_slot(&mut self, slot: SlotId) -> Result<(), BackendError> {
        self.0.create_slot(slot)
    }

    fn update(&mut self, slot: SlotId, state: &PlayerState) -> Result<(), BackendError> {
        self.0.update(slot, state)
    }

    fn reset(&mut self, slot: SlotId) -> Result<(), BackendError> {
        self.0.reset(slot)
    }

    fn destroy(&mut self, slot: SlotId) -> Result<(), BackendError> {
        self.0.destroy(slot)
    }

    fn slots(&self) -> Vec<SlotId> {
        self.0.slots()
    }
}

fn drive<T: Transport, B: GamepadBackend>(
    transport: &mut T,
    backend: &mut B,
    slot: SlotId,
) -> Result<usize, String> {
    transport.open().map_err(|error| format!("{:?}", error))?;
    backend
        .create_slot(slot)
        .map_err(|error| format!("{:?}", error))?;
    let mut frame = [0u8; FRAME_SIZE];
    let mut count = 0;
    while transport
        .read(&mut frame)
        .map_err(|error| format!("{:?}", error))?
    {
        let seq = u32::from_le_bytes([frame[12], frame[13], frame[14], frame[15]]);
        let timestamp_us = u64::from_le_bytes(frame[16..24].try_into().unwrap());
        let state = PlayerState {
            seq,
            timestamp_us,
            buttons: 1,
            ..PlayerState::neutral()
        };
        backend
            .update(slot, &state)
            .map_err(|error| format!("{:?}", error))?;
        count += 1;
    }
    Ok(count)
}

fn sample_frames() -> Vec<[u8; FRAME_SIZE]> {
    (1..=3)
        .map(|seq| synthetic_input_frame(1, seq, u64::from(seq) * 1_000))
        .collect()
}

#[test]
fn tc1_transport_substitution_keeps_orchestrator_unchanged() {
    let slot = SlotId::new(1).unwrap();
    let mut aoa = MockTransport::with_frames(sample_frames());
    let mut tethering = AlternateTransport(MockTransport::with_frames(sample_frames()));
    let mut aoa_backend = MockGamepad::recording();
    let mut tethering_backend = MockGamepad::recording();

    assert_eq!(drive(&mut aoa, &mut aoa_backend, slot).unwrap(), 3);
    assert_eq!(
        drive(&mut tethering, &mut tethering_backend, slot).unwrap(),
        3
    );
    assert_eq!(aoa_backend.updates(), tethering_backend.updates());
}

#[test]
fn tc2_backend_substitution_keeps_orchestrator_unchanged() {
    let slot = SlotId::new(1).unwrap();
    let mut first = MockTransport::with_frames(sample_frames());
    let mut second = MockTransport::with_frames(sample_frames());
    let mut uinput = MockGamepad::recording();
    let mut uhid = AlternateGamepad(MockGamepad::recording());

    assert_eq!(drive(&mut first, &mut uinput, slot).unwrap(), 3);
    assert_eq!(drive(&mut second, &mut uhid, slot).unwrap(), 3);
    assert_eq!(uinput.updates(), uhid.0.updates());
}

#[test]
fn tc3_mock_transport_produces_1000_frames_per_second() {
    let mut transport = MockTransport::new();
    transport.open().unwrap();
    transport.advance(1_000_000);
    let mut backend = MockGamepad::recording();
    let count = drive(&mut transport, &mut backend, SlotId::new(1).unwrap()).unwrap();
    assert_eq!(count, 1000);
    assert_eq!(backend.updates().len(), 1000);
}

#[test]
fn tc4_slots_stay_alive_and_neutral_after_disconnect() {
    let mut backend = MockGamepad::recording();
    for slot in SlotId::all() {
        backend.create_slot(slot).unwrap();
    }
    let player_two = SlotId::new(2).unwrap();
    backend
        .update(
            player_two,
            &PlayerState {
                buttons: 1,
                ..PlayerState::neutral()
            },
        )
        .unwrap();
    backend.reset(player_two).unwrap();

    assert_eq!(backend.slots().len(), 4);
    assert!(backend.slots().contains(&player_two));
    assert!(backend.updates().last().unwrap().1.is_neutral());

    backend.destroy(player_two).unwrap();
    assert_eq!(backend.slots().len(), 3);
    assert!(!backend.slots().contains(&player_two));
}

#[test]
fn tc5_transport_writes_exactly_one_frame() {
    let mut transport = MockTransport::new();
    transport.open().unwrap();
    let frame = synthetic_input_frame(1, 1, 0);
    transport.write(&frame).unwrap();
    assert_eq!(transport.writes().len(), 1);
    assert_eq!(transport.writes()[0], frame);
}

#[test]
fn tc6_neutral_state_has_no_buttons_or_axes() {
    let state = PlayerState::neutral();
    assert_eq!(state.buttons, 0);
    assert_eq!(state.lx, 0);
    assert_eq!(state.ly, 0);
    assert_eq!(state.rx, 0);
    assert_eq!(state.ry, 0);
    assert_eq!(state.lt, 0);
    assert_eq!(state.rt, 0);
}
