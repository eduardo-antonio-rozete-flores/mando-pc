pub const FRAME_SIZE: usize = 64;
pub const MAX_SLOTS: u8 = 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct SlotId(u8);

impl SlotId {
    pub fn new(value: u8) -> Option<Self> {
        if (1..=MAX_SLOTS).contains(&value) {
            Some(Self(value))
        } else {
            None
        }
    }

    pub fn get(self) -> u8 {
        self.0
    }

    pub fn all() -> [SlotId; 4] {
        [
            SlotId(1),
            SlotId(2),
            SlotId(3),
            SlotId(4),
        ]
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct SessionId(pub u32);

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TouchState {
    pub active: bool,
    pub id: u8,
    pub x: u16,
    pub y: u16,
    pub pressure: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PlayerState {
    pub timestamp_us: u64,
    pub seq: u32,
    pub buttons: u32,
    pub lx: i16,
    pub ly: i16,
    pub rx: i16,
    pub ry: i16,
    pub lt: u16,
    pub rt: u16,
    pub gyro: [i16; 3],
    pub accel: [i16; 3],
    pub touch: TouchState,
    pub battery: u8,
    pub link: u8,
    pub rtt_ms: u8,
}

impl PlayerState {
    pub fn neutral() -> Self {
        Self {
            timestamp_us: 0,
            seq: 0,
            buttons: 0,
            lx: 0,
            ly: 0,
            rx: 0,
            ry: 0,
            lt: 0,
            rt: 0,
            gyro: [0; 3],
            accel: [0; 3],
            touch: TouchState::default(),
            battery: 255,
            link: 0,
            rtt_ms: 0,
        }
    }

    pub fn is_neutral(&self) -> bool {
        *self == Self::neutral()
    }
}

impl Default for PlayerState {
    fn default() -> Self {
        Self::neutral()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[repr(u32)]
pub enum ButtonId {
    A = 0,
    B = 1,
    X = 2,
    Y = 3,
    LB = 4,
    RB = 5,
    Back = 6,
    Start = 7,
    Guide = 8,
    L3 = 9,
    R3 = 10,
    DpadUp = 11,
    DpadDown = 12,
    DpadLeft = 13,
    DpadRight = 14,
    Touchpad = 15,
    Capture = 16,
}

impl ButtonId {
    pub fn mask(self) -> u32 {
        1 << (self as u32)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum StickId {
    Left,
    Right,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum TriggerId {
    Left,
    Right,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InputEvent {
    Button { id: ButtonId, pressed: bool },
    Stick { id: StickId, x: i16, y: i16 },
    Trigger { id: TriggerId, value: u16 },
    Touch(TouchState),
    Gyro([i16; 3]),
    Accel([i16; 3]),
    Battery(u8),
    Link(u8),
}

pub fn apply_event(state: &mut PlayerState, event: InputEvent) {
    match event {
        InputEvent::Button { id, pressed } => {
            if pressed {
                state.buttons |= id.mask();
            } else {
                state.buttons &= !id.mask();
            }
        }
        InputEvent::Stick { id, x, y } => match id {
            StickId::Left => {
                state.lx = x;
                state.ly = y;
            }
            StickId::Right => {
                state.rx = x;
                state.ry = y;
            }
        },
        InputEvent::Trigger { id, value } => match id {
            TriggerId::Left => state.lt = value,
            TriggerId::Right => state.rt = value,
        },
        InputEvent::Touch(touch) => state.touch = touch,
        InputEvent::Gyro(value) => state.gyro = value,
        InputEvent::Accel(value) => state.accel = value,
        InputEvent::Battery(value) => state.battery = value,
        InputEvent::Link(value) => state.link = value,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DisconnectReason {
    Normal,
    AppClosed,
    CableRemoved,
    Timeout,
    Error,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TransportError {
    NotOpen,
    Disconnected { reason: DisconnectReason },
    Io(String),
}

pub trait Transport {
    fn open(&mut self) -> Result<(), TransportError>;
    fn close(&mut self) -> Result<(), TransportError>;
    fn read(&mut self, out: &mut [u8; FRAME_SIZE]) -> Result<bool, TransportError>;
    fn write(&mut self, frame: &[u8; FRAME_SIZE]) -> Result<(), TransportError>;
    fn on_disconnect(&mut self) -> Option<DisconnectReason>;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BackendError {
    SlotNotFound(SlotId),
    AlreadyExists(SlotId),
    Io(String),
}

pub trait GamepadBackend {
    fn create_slot(&mut self, slot: SlotId) -> Result<(), BackendError>;
    fn update(&mut self, slot: SlotId, state: &PlayerState) -> Result<(), BackendError>;
    fn reset(&mut self, slot: SlotId) -> Result<(), BackendError>;
    fn destroy(&mut self, slot: SlotId) -> Result<(), BackendError>;
    fn slots(&self) -> Vec<SlotId>;
}

pub trait InputSource {
    fn poll(&mut self, now_us: u64) -> Option<InputEvent>;
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slot_ids_are_limited_to_four() {
        assert!(SlotId::new(0).is_none());
        assert!(SlotId::new(5).is_none());
        assert_eq!(SlotId::new(1).unwrap().get(), 1);
        assert_eq!(SlotId::all().len(), 4);
    }

    #[test]
    fn button_event_sets_and_clears_mask() {
        let mut state = PlayerState::neutral();
        apply_event(
            &mut state,
            InputEvent::Button {
                id: ButtonId::A,
                pressed: true,
            },
        );
        assert_eq!(state.buttons & ButtonId::A.mask(), 1);
        apply_event(
            &mut state,
            InputEvent::Button {
                id: ButtonId::A,
                pressed: false,
            },
        );
        assert_eq!(state.buttons, 0);
    }

    #[test]
    fn neutral_state_is_deterministic() {
        let state = PlayerState::neutral();
        assert!(state.is_neutral());
        assert_eq!(state.battery, 255);
        assert_eq!(state.buttons, 0);
    }
}
