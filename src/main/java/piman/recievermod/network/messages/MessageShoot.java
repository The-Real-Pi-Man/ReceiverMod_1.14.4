package piman.recievermod.network.messages;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;
import piman.recievermod.items.guns.ItemGun;

public class MessageShoot extends MessageBase<MessageShoot> {
	
	CompoundNBT nbt;
	double damage;
	float accuracy1;
	float accuracy2;
	int life;
	int bullets;

		
	public MessageShoot() {}
	
	public MessageShoot(CompoundNBT nbt, double damage, float accuracy1, float accuracy2, int life, int bullets) {

		this.nbt = nbt;
		this.damage = damage;
		this.accuracy1 = accuracy1;
		this.accuracy2 = accuracy2;
		this.life = life;
		this.bullets = bullets;

	}

	@Override
	public MessageShoot fromBytes(PacketBuffer buf) {

		MessageShoot message = new MessageShoot();
		
		message.nbt = buf.readCompoundTag();
		message.damage = buf.readDouble();
		message.accuracy1 = buf.readFloat();
		message.accuracy2 = buf.readFloat();
		message.life = buf.readInt();
		message.bullets = buf.readInt();

		return message;
	}

	@Override
	public void toBytes(MessageShoot message, PacketBuffer buf) {
		
		buf.writeCompoundTag(message.nbt);
		buf.writeDouble(message.damage);
		buf.writeFloat(message.accuracy1);
		buf.writeFloat(message.accuracy2);
		buf.writeInt(message.life);
		buf.writeInt(message.bullets);

	}

	@Override
	public void handleClientSide(MessageShoot message, PlayerEntity player) {

	}

	@Override
	public void handleServerSide(MessageShoot message, PlayerEntity player) {
		
		//System.out.println("MessageShoot Received" + message.nbt);
		((ItemGun) player.getHeldItemMainhand().getItem()).Shoot(message.nbt, player, message.damage, message.accuracy1, message.accuracy2, message.bullets, message.life);
	
	}

}
