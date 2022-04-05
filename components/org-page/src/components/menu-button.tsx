import React from 'react'
import useDropdownMenu from './menu-items'
import styles from '../styles.module.css'

export interface MenubuttonProps {
    title: string
    onClick?: () => void
}

export interface ButtonsProps {
    buttons: MenubuttonProps[]
}

const MenuButton = (props: ButtonsProps) => {
    const { setIsOpen, isOpen } = useDropdownMenu(3)

    return (
        <div>
            <button onClick={() => setIsOpen(!isOpen)} type='button'>
                Setting
            </button>

            <div className={isOpen ? styles.menuVisible : styles.menu}>
                {
                    props.buttons.map((button, index) => (
                        <a className={styles.menuItem} onClick={() => button.onClick?.()} key={`menu-item-${index}`}>
                            {button.title}
                        </a>
                    ))
                }
            </div>
        </div>
    )
}

export default MenuButton