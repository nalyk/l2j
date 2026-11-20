/*
 * Copyright (c) 2013 L2jMobius
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.mechanics.variables;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.l2jmobius.gameserver.util.StatSet;

/**
 * @author UnAfraid, Mobius
 */
public abstract class AbstractVariables extends StatSet
{
	private final AtomicBoolean _hasChanges = new AtomicBoolean(false);
	
	protected final Set<String> _added = new HashSet<>(4);
	protected final Set<String> _modified = new HashSet<>(4);
	protected final Set<String> _deleted = new HashSet<>(4);
	protected final Lock _saveLock = new ReentrantLock();
	/** Taken by the Asset Service with its item (CP-0013): refuses every write and every save from then on. */
	private volatile boolean _retired;
	
	protected AbstractVariables()
	{
		super(ConcurrentHashMap::new);
	}
	
	/**
	 * Overriding following methods to prevent from doing useless database operations if there is no changes since player's login.
	 */
	
	@Override
	public void set(String name, boolean value)
	{
		setTracked(name, () -> super.set(name, value));
	}
	
	@Override
	public void set(String name, byte value)
	{
		setTracked(name, () -> super.set(name, value));
	}
	
	@Override
	public void set(String name, short value)
	{
		setTracked(name, () -> super.set(name, value));
	}
	
	@Override
	public void set(String name, int value)
	{
		setTracked(name, () -> super.set(name, value));
	}
	
	@Override
	public void set(String name, long value)
	{
		setTracked(name, () -> super.set(name, value));
	}
	
	@Override
	public void set(String name, float value)
	{
		setTracked(name, () -> super.set(name, value));
	}
	
	@Override
	public void set(String name, double value)
	{
		setTracked(name, () -> super.set(name, value));
	}
	
	@Override
	public void set(String name, String value)
	{
		setTracked(name, () -> super.set(name, value));
	}
	
	@Override
	public void set(String name, Enum<?> value)
	{
		setTracked(name, () -> super.set(name, value));
	}
	
	@Override
	public void set(String name, Object value)
	{
		setTracked(name, () -> super.set(name, value));
	}
	
	/**
	 * Tracks and applies one change in a single step under the save lock (CP-0013), so no save or capture ever sees a
	 * change tracked without its value; a retired object refuses it.
	 * @param name the name of the variable
	 * @param put the change itself
	 */
	private void setTracked(String name, Runnable put)
	{
		_saveLock.lock();
		try
		{
			if (_retired)
			{
				return;
			}
			
			trackChange(name);
			put.run();
		}
		finally
		{
			_saveLock.unlock();
		}
	}
	
	/**
	 * Retires these variables (CP-0013): the Asset Service has taken their item; from now on every write is refused and
	 * nothing is saved, since the service's own transaction writes their rows.
	 */
	public void retire()
	{
		_saveLock.lock();
		try
		{
			_retired = true;
		}
		finally
		{
			_saveLock.unlock();
		}
	}
	
	/**
	 * @return whether the Asset Service has taken these variables' item (CP-0013)
	 */
	public boolean isRetired()
	{
		return _retired;
	}
	
	/**
	 * Adds every entry of {@code newSet}, each tracked (CP-0013: upstream's {@code merge} bypassed tracking).
	 */
	@Override
	public void merge(StatSet newSet)
	{
		_saveLock.lock();
		try
		{
			if (_retired)
			{
				return;
			}
			
			for (String name : newSet.getSet().keySet())
			{
				trackChange(name);
			}
			super.merge(newSet);
		}
		finally
		{
			_saveLock.unlock();
		}
	}
	
	/**
	 * Track variable changes for optimized database operations.
	 * @param name the name of the variable
	 */
	protected void trackChange(String name)
	{
		_saveLock.lock();
		
		try
		{
			_hasChanges.compareAndSet(false, true);
			
			if (hasVariable(name))
			{
				_modified.add(name);
				_deleted.remove(name);
			}
			else
			{
				_added.add(name);
			}
		}
		finally
		{
			_saveLock.unlock();
		}
	}
	
	/**
	 * Put's entry to the variables and marks as changed if required (<i>Useful when restoring to do not save them again</i>).
	 * @param name the name of the variable
	 * @param value the value to set
	 * @param markAsChanged
	 */
	public void set(String name, String value, boolean markAsChanged)
	{
		_saveLock.lock();
		try
		{
			if (_retired)
			{
				return;
			}
			
			if (markAsChanged)
			{
				trackChange(name);
			}
			
			super.set(name, value);
		}
		finally
		{
			_saveLock.unlock();
		}
	}
	
	/**
	 * Return true if there exists a record for the variable name.
	 * @param name the name of the variable
	 * @return if the variable exists
	 */
	public boolean hasVariable(String name)
	{
		return getSet().containsKey(name);
	}
	
	/**
	 * @return {@code true} if changes are made since last load/save.
	 */
	public boolean hasChanges()
	{
		return _hasChanges.get();
	}
	
	/**
	 * Atomically sets the value to the given updated value if the current value {@code ==} the expected value.
	 * @param expect the extpected value
	 * @param update the update value
	 * @return {@code true} if successful. {@code false} return indicates that the actual value was not equal to the expected value.
	 */
	public boolean compareAndSetChanges(boolean expect, boolean update)
	{
		return _hasChanges.compareAndSet(expect, update);
	}
	
	/**
	 * Removes the given variable.
	 * @param name the name of the variable
	 */
	@Override
	public void remove(String name)
	{
		_saveLock.lock();
		
		try
		{
			if (_retired)
			{
				return; // CP-0013
			}
			
			_hasChanges.compareAndSet(false, true);
			
			// First remove from map.
			final Object removed = getSet().remove(name);
			
			// Then update tracking based on whether it actually existed.
			if (removed != null)
			{
				// If the variable was newly added during this session and never persisted, removing it should simply drop it from the added set without scheduling a DB DELETE (there's nothing to delete).
				if (_added.remove(name))
				{
					// It was new and never persisted -> nothing more to do.
					return;
				}
				
				_modified.remove(name);
				_deleted.add(name);
			}
		}
		finally
		{
			_saveLock.unlock();
		}
	}
	
	/**
	 * Clears change tracking sets.
	 */
	protected void clearChangeTracking()
	{
		_added.clear();
		_modified.clear();
		_deleted.clear();
	}
}
