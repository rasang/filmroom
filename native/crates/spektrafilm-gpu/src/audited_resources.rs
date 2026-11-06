//! Audit-only wrappers. Each raw GPU object is owned exactly once, before its
//! numeric observer field so normal destruction releases the API object first.
use crate::resource_audit::resident_owners::{
    self, Kind, Owner
};
use std::ops::Deref;
pub(super) struct Buffer {
    raw: wgpu::Buffer,
    owner: Owner,
    pending_on_unmap: std::cell::Cell<bool>
}

impl Buffer {
    pub(super) fn new(raw: wgpu::Buffer, pending_on_unmap: bool) -> Self {
        let owner = Owner::buffer(raw.size());
        Self {
            raw, owner, pending_on_unmap: std::cell::Cell::new(pending_on_unmap)
        }
    }
    pub(super) fn unmap(&self) {
        if self.pending_on_unmap.replace(false) {
            self.queue_write();
        }
        self.raw.unmap();
    }
    pub(super) fn as_entire_binding(&self) -> Binding<'_> {
        Binding {
            buffer: self
        }
    }
    pub(super) fn queue_write(&self) {
        resident_owners::queue_write(&self.owner);
    }
}

impl Deref for Buffer {
    type Target = wgpu::Buffer;
    fn deref(&self) -> &Self::Target {
        &self.raw
    }
}
pub(super) struct Binding<'a> {
    buffer: &'a Buffer
}
pub(super) struct BindGroupEntry<'a> {
    pub binding: u32,
    pub resource: Binding<'a>
}
pub(super) struct BindGroupDescriptor<'a> {
    pub label: Option<&'a str>,
    pub layout: &'a wgpu::BindGroupLayout,
    pub entries: &'a [BindGroupEntry<'a>]
}
pub(super) struct BindGroup {
    raw: wgpu::BindGroup,
    owner: Owner
}
pub(super) fn bind_group(device: &wgpu::Device, desc: &BindGroupDescriptor<'_>) -> BindGroup {
    let owner = Owner::new(Kind::BindGroup);
    for entry in desc.entries {
        owner.retain(&entry.resource.buffer.owner);
    }
    let entries: Vec<_> = desc.entries.iter().map(|entry| wgpu::BindGroupEntry {
        binding: entry.binding, resource: entry.resource.buffer.raw.as_entire_binding(),
    }).collect();
    let raw = device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: desc.label, layout: desc.layout, entries: &entries
    });
    BindGroup {
        raw, owner
    }
}
pub(super) struct CommandEncoder {
    raw: wgpu::CommandEncoder,
    owner: Owner
}
pub(super) struct CommandBuffer {
    pub raw: wgpu::CommandBuffer,
    pub owner: Owner
}
pub(super) fn encoder(device: &wgpu::Device, desc: &wgpu::CommandEncoderDescriptor<'_>) -> CommandEncoder {
    let raw = device.create_command_encoder(desc);
    CommandEncoder {
        raw, owner: Owner::new(Kind::Encoder)
    }
}

impl CommandEncoder {
    pub(super) fn begin_compute_pass<'a>(&'a mut self, desc: &wgpu::ComputePassDescriptor<'_>) -> ComputePass<'a> {
        ComputePass {
            raw: self.raw.begin_compute_pass(desc), owner: &self.owner
        }
    }
    pub(super) fn copy_buffer_to_buffer(&mut self, source: &Buffer, source_offset: u64, destination: &Buffer, destination_offset: u64, size: u64) {
        self.owner.retain(&source.owner);
        self.owner.retain(&destination.owner);
        self.raw.copy_buffer_to_buffer(&source.raw, source_offset, &destination.raw, destination_offset, size);
    }
    pub(super) fn clear_buffer(&mut self, buffer: &Buffer, offset: u64, size: Option<u64>) {
        self.owner.retain(&buffer.owner);
        self.raw.clear_buffer(&buffer.raw, offset, size);
    }
    pub(super) fn finish(self) -> CommandBuffer {
        let raw = self.raw.finish();
        let owner = self.owner.transfer(Kind::Command);
        CommandBuffer {
            raw, owner
        }
    }
}
pub(super) struct ComputePass<'a> {
    raw: wgpu::ComputePass<'a>,
    owner: &'a Owner
}

impl ComputePass<'_> {
    pub(super) fn set_pipeline(&mut self, pipeline: &wgpu::ComputePipeline) {
        self.raw.set_pipeline(pipeline);
    }
    pub(super) fn set_bind_group(&mut self, index: u32, group: &BindGroup, offsets: &[u32]) {
        self.owner.retain(&group.owner);
        self.raw.set_bind_group(index, &group.raw, offsets);
    }
    pub(super) fn dispatch_workgroups(&mut self, x: u32, y: u32, z: u32) {
        self.raw.dispatch_workgroups(x, y, z);
    }
}
